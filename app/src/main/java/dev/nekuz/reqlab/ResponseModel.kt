package dev.nekuz.reqlab

import java.nio.charset.Charset
import java.util.Locale

data class RedirectHop(val status: Int, val from: String, val to: String, val droppedHeaders: List<String>)

class RawResponse(
    val status: Int,
    val message: String?,
    val headers: List<Kv>,
    val body: ByteArray,
    val truncated: Boolean,
    val elapsedMs: Long,
    val finalUrl: String,
    val hops: List<RedirectHop>,
    val notes: List<String>,
)

enum class StatusClass {
    INFORMATIONAL, SUCCESS, REDIRECT, CLIENT_ERROR, SERVER_ERROR, UNKNOWN;

    companion object {
        fun of(status: Int): StatusClass = when (status) {
            in 100..199 -> INFORMATIONAL
            in 200..299 -> SUCCESS
            in 300..399 -> REDIRECT
            in 400..499 -> CLIENT_ERROR
            in 500..599 -> SERVER_ERROR
            else -> UNKNOWN
        }
    }
}

enum class BodyKind { EMPTY, TEXT, JSON, BINARY }

data class ResponseView(
    val statusLine: String,
    val statusClass: StatusClass,
    val time: String,
    val sizeBytes: Long,
    val truncated: Boolean,
    val finalUrl: String,
    val headers: List<Kv>,
    val hops: List<String>,
    val notes: List<String>,
    val bodyKind: BodyKind,
    val bodyChunks: List<String>,
    val copyText: String,
    val copyCut: Boolean,
)

object BodyDecoder {
    private const val SAMPLE = 8_192
    private const val MAX_CHARSET_NAME = 64
    private val CHARSET = Regex("charset\\s*=\\s*\"?([A-Za-z0-9._:+-]+)\"?", RegexOption.IGNORE_CASE)

    fun charset(contentType: String?): Charset {
        if (contentType == null) return Charsets.UTF_8
        val name = CHARSET.find(contentType.take(512))?.groupValues?.get(1) ?: return Charsets.UTF_8
        if (name.length > MAX_CHARSET_NAME) return Charsets.UTF_8
        return try {
            Charset.forName(name)
        } catch (e: IllegalArgumentException) {
            Charsets.UTF_8
        }
    }

    fun looksBinary(bytes: ByteArray, charset: Charset): Boolean {
        val name = charset.name().uppercase(Locale.ROOT)
        if (name.startsWith("UTF-16") || name.startsWith("UTF-32")) return false
        val end = minOf(bytes.size, SAMPLE)
        if (end == 0) return false
        var control = 0
        for (index in 0 until end) {
            val value = bytes[index].toInt() and 0xFF
            if (value == 0) return true
            if (value < 9 || value in 14..31 || value == 127) control++
        }
        return control * 10 > end
    }

    fun decode(bytes: ByteArray, charset: Charset): String {
        val text = String(bytes, charset)
        return if (text.startsWith("\uFEFF")) text.substring(1) else text
    }
}

object BodyChunks {
    const val CHUNK = 3_000

    fun split(text: String, chunk: Int = CHUNK): List<String> {
        if (text.isEmpty()) return emptyList()
        val chunks = ArrayList<String>(text.length / chunk + 1)
        var start = 0
        while (start < text.length) {
            var end = minOf(start + chunk, text.length)
            if (end < text.length) {
                val newline = lastNewline(text, start, end)
                if (newline > start) {
                    chunks.add(text.substring(start, newline))
                    start = newline + 1
                    continue
                }
                if (Character.isHighSurrogate(text[end - 1]) && end - 1 > start) end--
            }
            chunks.add(text.substring(start, end))
            start = end
        }
        return chunks
    }

    private fun lastNewline(text: String, start: Int, end: Int): Int {
        var index = end - 1
        while (index > start) {
            if (text[index] == '\n') return index
            index--
        }
        return -1
    }
}

object ResponseViews {
    private const val MAX_STATUS_MESSAGE = 200
    private const val MAX_HEADER_NAME = 128
    private const val MAX_HEADER_VALUE = 4_096
    private const val MAX_URL_SHOWN = 2_048
    private const val MAX_TEXT = 6_000_000

    fun build(raw: RawResponse, maskUrl: (String) -> String = Redactor::url): ResponseView {
        val headers = raw.headers.take(Limits.MAX_RESPONSE_HEADERS).map {
            Kv(ForeignText.label(it.key, "(unnamed)").take(MAX_HEADER_NAME), ForeignText.clean(it.value, MAX_HEADER_VALUE))
        }
        val notes = ArrayList(raw.notes.map { ForeignText.clean(it, ForeignText.MAX_VALUE) })
        if (raw.headers.size > Limits.MAX_RESPONSE_HEADERS) {
            notes.add("Only the first ${Limits.MAX_RESPONSE_HEADERS} headers are shown.")
        }
        val contentType = raw.headers.firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }?.value
        val message = ForeignText.clean(raw.message, MAX_STATUS_MESSAGE)
        val decoded = decodeBody(raw.body, contentType)
        return ResponseView(
            statusLine = if (message.isEmpty()) raw.status.toString() else "${raw.status} $message",
            statusClass = StatusClass.of(raw.status),
            time = Text.millis(raw.elapsedMs),
            sizeBytes = raw.body.size.toLong(),
            truncated = raw.truncated,
            finalUrl = ForeignText.clean(maskUrl(raw.finalUrl), MAX_URL_SHOWN),
            headers = headers,
            hops = raw.hops.map { hopText(it, maskUrl) },
            notes = notes,
            bodyKind = decoded.first,
            bodyChunks = BodyChunks.split(decoded.second),
            copyText = copyable(decoded.second),
            copyCut = decoded.second.length > Limits.MAX_COPY_CHARS,
        )
    }

    private fun decodeBody(bytes: ByteArray, contentType: String?): Pair<BodyKind, String> {
        if (bytes.isEmpty()) return BodyKind.EMPTY to ""
        val charset = BodyDecoder.charset(contentType)
        if (BodyDecoder.looksBinary(bytes, charset)) return BodyKind.BINARY to ""
        val text = BodyDecoder.decode(bytes, charset)
        val first = text.firstOrNull { !it.isWhitespace() }
        val jsonLike = first == '{' || first == '[' || contentType?.contains("json", ignoreCase = true) == true
        if (jsonLike) {
            JsonPretty.format(text.trim())?.let { return BodyKind.JSON to ForeignText.block(it, MAX_TEXT) }
        }
        return BodyKind.TEXT to ForeignText.block(text, MAX_TEXT)
    }

    private fun copyable(text: String): String {
        if (text.length <= Limits.MAX_COPY_CHARS) return text
        val cap = Limits.MAX_COPY_CHARS
        return text.substring(0, if (Character.isHighSurrogate(text[cap - 1])) cap - 1 else cap)
    }

    private fun hopText(hop: RedirectHop, maskUrl: (String) -> String): String {
        val line = "${hop.status}: ${ForeignText.clean(maskUrl(hop.from), MAX_URL_SHOWN)} to ${ForeignText.clean(maskUrl(hop.to), MAX_URL_SHOWN)}"
        return if (hop.droppedHeaders.isEmpty()) {
            line
        } else {
            line + " (dropped " + hop.droppedHeaders.joinToString(", ") { ForeignText.clean(it, 64) } + ")"
        }
    }
}
