package dev.nekuz.reqlab

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64
import java.util.UUID

object TextFiles {
    private const val BUFFER = 8_192

    fun read(file: File, maxBytes: Int): String? {
        return try {
            if (!file.isFile) return null
            FileInputStream(file).use { stream ->
                val out = ByteArrayOutputStream(BUFFER)
                val buffer = ByteArray(BUFFER)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    if (out.size() + count > maxBytes) return null
                    out.write(buffer, 0, count)
                }
                String(out.toByteArray(), Charsets.UTF_8)
            }
        } catch (e: IOException) {
            null
        } catch (e: SecurityException) {
            null
        }
    }

    fun write(file: File, text: String): Boolean {
        val temp = File(file.parentFile, file.name + ".tmp")
        return try {
            file.parentFile?.mkdirs()
            FileOutputStream(temp).use { stream ->
                stream.write(text.toByteArray(Charsets.UTF_8))
                stream.flush()
                stream.fd.sync()
            }
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            true
        } catch (e: IOException) {
            temp.delete()
            false
        } catch (e: SecurityException) {
            false
        }
    }

    fun quarantine(file: File): Boolean {
        return try {
            if (!file.exists()) return true
            Files.move(file.toPath(), corruptFile(file).toPath(), StandardCopyOption.REPLACE_EXISTING)
            true
        } catch (e: IOException) {
            false
        } catch (e: SecurityException) {
            false
        }
    }

    fun corruptFile(file: File) = File(file.parentFile, file.name + ".corrupt")

    fun delete(file: File): Boolean {
        return try {
            File(file.parentFile, file.name + ".tmp").delete()
            corruptFile(file).delete()
            !file.exists() || file.delete()
        } catch (e: SecurityException) {
            false
        }
    }
}

interface Sealer {
    fun seal(plain: ByteArray): String?

    fun open(sealed: String): ByteArray?
}

object Envelope {
    private const val PREFIX = "v1"
    private const val SEPARATOR = '.'

    fun encode(iv: ByteArray, ciphertext: ByteArray): String {
        val encoder = Base64.getEncoder()
        return PREFIX + SEPARATOR + encoder.encodeToString(iv) + SEPARATOR + encoder.encodeToString(ciphertext)
    }

    fun decode(text: String): Pair<ByteArray, ByteArray>? {
        val parts = text.split(SEPARATOR)
        if (parts.size != 3 || parts[0] != PREFIX) return null
        return try {
            val decoder = Base64.getDecoder()
            val iv = decoder.decode(parts[1])
            val ciphertext = decoder.decode(parts[2])
            if (iv.isEmpty() || ciphertext.isEmpty()) null else iv to ciphertext
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}

object SpecCodec {
    fun toJson(spec: RequestSpec): JSONObject = JSONObject()
        .put("method", spec.method.name)
        .put("url", spec.url)
        .put("params", rowsToJson(spec.params))
        .put("headers", rowsToJson(spec.headers))
        .put("authType", spec.auth.type.name)
        .put("token", spec.auth.token)
        .put("username", spec.auth.username)
        .put("password", spec.auth.password)
        .put("bodyType", spec.bodyType.name)
        .put("body", spec.bodyText)
        .put("form", rowsToJson(spec.formFields))
        .put("follow", spec.followRedirects)

    fun fromJson(json: JSONObject): RequestSpec = RequestSpec(
        method = HttpMethod.parse(json.optString("method")) ?: HttpMethod.GET,
        url = json.optString("url").take(Limits.MAX_STORED_VALUE),
        params = rowsFromJson(json.optJSONArray("params")),
        headers = rowsFromJson(json.optJSONArray("headers")),
        auth = Auth(
            type = AuthType.parse(json.optString("authType")) ?: AuthType.NONE,
            token = json.optString("token").take(Limits.MAX_STORED_VALUE),
            username = json.optString("username").take(Limits.MAX_STORED_VALUE),
            password = json.optString("password").take(Limits.MAX_STORED_VALUE),
        ),
        bodyType = BodyType.parse(json.optString("bodyType")) ?: BodyType.RAW,
        bodyText = json.optString("body").take(Limits.MAX_STORED_VALUE),
        formFields = rowsFromJson(json.optJSONArray("form")),
        followRedirects = json.optBoolean("follow", false),
    )

    fun rowsToJson(rows: List<Kv>): JSONArray {
        val array = JSONArray()
        rows.forEach { array.put(JSONObject().put("k", it.key).put("v", it.value)) }
        return array
    }

    fun rowsFromJson(array: JSONArray?, maxRows: Int = Limits.MAX_ROWS): List<Kv> {
        if (array == null) return emptyList()
        val rows = ArrayList<Kv>()
        for (index in 0 until minOf(array.length(), maxRows)) {
            val row = array.optJSONObject(index) ?: continue
            rows.add(Kv(row.optString("k").take(Limits.MAX_STORED_KEY), row.optString("v").take(Limits.MAX_STORED_VALUE)))
        }
        return rows
    }

    fun parseObject(text: String?): JSONObject? {
        if (text == null) return null
        return try {
            JSONObject(text)
        } catch (e: JSONException) {
            null
        } catch (e: RuntimeException) {
            null
        } catch (e: StackOverflowError) {
            null
        }
    }

    fun parseArray(text: String?): JSONArray? {
        if (text == null) return null
        return try {
            JSONArray(text)
        } catch (e: JSONException) {
            null
        } catch (e: RuntimeException) {
            null
        } catch (e: StackOverflowError) {
            null
        }
    }

    fun seal(sealer: Sealer, spec: RequestSpec): String? =
        sealer.seal(toJson(spec).toString().toByteArray(Charsets.UTF_8))

    fun open(sealer: Sealer, sealed: String): RequestSpec? {
        val plain = sealer.open(sealed) ?: return null
        return parseObject(String(plain, Charsets.UTF_8))?.let(::fromJson)
    }
}

enum class SaveResult { SAVED, SEAL_FAILED, FULL, WRITE_FAILED, BAD_NAME }

data class SavedItem(
    val id: String,
    val name: String,
    val collection: String,
    val method: HttpMethod,
    val updatedAt: Long,
    val sealed: String,
)

class SavedStore(private val file: File, private val sealer: Sealer) {
    private var cache: List<SavedItem>? = null
    private var damaged = false

    @Synchronized
    fun list(): List<SavedItem> = load()

    @Synchronized
    fun save(rawName: String, rawCollection: String, spec: RequestSpec, now: Long): SaveResult {
        val name = ForeignText.clean(rawName, Limits.MAX_NAME)
        if (name.isEmpty()) return SaveResult.BAD_NAME
        val collection = ForeignText.clean(rawCollection, Limits.MAX_NAME).ifEmpty { DEFAULT_COLLECTION }
        val sealed = SpecCodec.seal(sealer, spec) ?: return SaveResult.SEAL_FAILED
        val current = load()
        if (damaged) return SaveResult.WRITE_FAILED
        val existing = current.firstOrNull { it.name == name && it.collection == collection }
        if (existing == null && current.size >= MAX_ITEMS) return SaveResult.FULL
        val item = SavedItem(existing?.id ?: UUID.randomUUID().toString(), name, collection, spec.method, now, sealed)
        val next = current.filterNot { it.id == item.id } + item
        val text = encode(next)
        if (text.toByteArray(Charsets.UTF_8).size > MAX_FILE_BYTES) return SaveResult.FULL
        if (!TextFiles.write(file, text)) return SaveResult.WRITE_FAILED
        cache = next
        return SaveResult.SAVED
    }

    @Synchronized
    fun open(id: String): RequestSpec? {
        val item = load().firstOrNull { it.id == id } ?: return null
        return SpecCodec.open(sealer, item.sealed)
    }

    @Synchronized
    fun delete(id: String): Boolean = replace(load().filterNot { it.id == id })

    @Synchronized
    fun deleteCollection(collection: String): Boolean = replace(load().filterNot { it.collection == collection })

    private fun replace(next: List<SavedItem>): Boolean {
        if (damaged) return false
        if (!TextFiles.write(file, encode(next))) return false
        cache = next
        return true
    }

    private fun load(): List<SavedItem> {
        cache?.let { return it }
        val array = SpecCodec.parseArray(TextFiles.read(file, MAX_FILE_BYTES))
        if (array == null && file.exists()) damaged = !TextFiles.quarantine(file)
        val items = ArrayList<SavedItem>()
        if (array != null) {
            for (index in 0 until minOf(array.length(), MAX_ITEMS)) {
                val row = array.optJSONObject(index) ?: continue
                val id = row.optString("id")
                val sealed = row.optString("sealed")
                val name = ForeignText.clean(row.optString("name"), Limits.MAX_NAME)
                if (id.isEmpty() || sealed.isEmpty() || name.isEmpty()) continue
                items.add(
                    SavedItem(
                        id = id,
                        name = name,
                        collection = ForeignText.clean(row.optString("collection"), Limits.MAX_NAME).ifEmpty { DEFAULT_COLLECTION },
                        method = HttpMethod.parse(row.optString("method")) ?: HttpMethod.GET,
                        updatedAt = row.optLong("updatedAt"),
                        sealed = sealed,
                    ),
                )
            }
        }
        cache = items
        return items
    }

    private fun encode(items: List<SavedItem>): String {
        val array = JSONArray()
        items.forEach {
            array.put(
                JSONObject()
                    .put("id", it.id)
                    .put("name", it.name)
                    .put("collection", it.collection)
                    .put("method", it.method.name)
                    .put("updatedAt", it.updatedAt)
                    .put("sealed", it.sealed),
            )
        }
        return array.toString()
    }

    companion object {
        const val DEFAULT_COLLECTION = "Default"
        const val MAX_ITEMS = 500
        const val MAX_FILE_BYTES = 16 * 1024 * 1024
    }
}

data class EnvironmentInfo(val id: String, val name: String, val sealed: String)

class EnvironmentStore(private val file: File, private val sealer: Sealer) {
    private var loaded = false
    private var items: List<EnvironmentInfo> = emptyList()
    private var active: String? = null
    private var damaged = false

    @Synchronized
    fun list(): List<EnvironmentInfo> {
        load()
        return items
    }

    @Synchronized
    fun activeId(): String? {
        load()
        return active
    }

    @Synchronized
    fun setActive(id: String?): Boolean {
        load()
        val target = id?.takeIf { wanted -> items.any { it.id == wanted } }
        val previous = active
        active = target
        if (write()) return true
        active = previous
        return false
    }

    @Synchronized
    fun find(id: String): EnvironmentInfo? {
        load()
        return items.firstOrNull { it.id == id }
    }

    @Synchronized
    fun create(name: String): String? {
        load()
        if (items.size >= MAX_ENVIRONMENTS) return null
        val sealed = sealVariables(emptyList()) ?: return null
        val id = UUID.randomUUID().toString()
        val previous = items
        items = items + EnvironmentInfo(id, ForeignText.clean(name, Limits.MAX_NAME).ifEmpty { DEFAULT_NAME }, sealed)
        if (write()) return id
        items = previous
        return null
    }

    @Synchronized
    fun update(id: String, name: String, rows: List<Kv>): Boolean {
        load()
        if (items.none { it.id == id }) return false
        if (rows.size > MAX_VARIABLES || rows.any { it.key.length > Limits.MAX_STORED_KEY || it.value.length > Limits.MAX_STORED_VALUE }) return false
        val sealed = sealVariables(rows) ?: return false
        val previous = items
        val cleanName = ForeignText.clean(name, Limits.MAX_NAME).ifEmpty { DEFAULT_NAME }
        items = items.map { if (it.id == id) EnvironmentInfo(id, cleanName, sealed) else it }
        if (write()) return true
        items = previous
        return false
    }

    @Synchronized
    fun delete(id: String): Boolean {
        load()
        val previousItems = items
        val previousActive = active
        items = items.filterNot { it.id == id }
        if (active == id) active = null
        if (write()) return true
        items = previousItems
        active = previousActive
        return false
    }

    @Synchronized
    fun rows(id: String): List<Kv>? {
        val item = find(id) ?: return null
        val plain = sealer.open(item.sealed) ?: return null
        val array = SpecCodec.parseArray(String(plain, Charsets.UTF_8)) ?: return null
        return SpecCodec.rowsFromJson(array, MAX_VARIABLES)
    }

    @Synchronized
    fun activeVariables(): Map<String, String> {
        val id = activeId() ?: return emptyMap()
        return Variables.toMap(rows(id).orEmpty())
    }

    private fun sealVariables(rows: List<Kv>): String? =
        sealer.seal(SpecCodec.rowsToJson(rows.take(MAX_VARIABLES)).toString().toByteArray(Charsets.UTF_8))

    private fun load() {
        if (loaded) return
        loaded = true
        val json = SpecCodec.parseObject(TextFiles.read(file, MAX_FILE_BYTES))
        val array = json?.optJSONArray("items")
        if (json == null || array == null) {
            if (file.exists()) damaged = !TextFiles.quarantine(file)
            return
        }
        val list = ArrayList<EnvironmentInfo>()
        for (index in 0 until minOf(array.length(), MAX_ENVIRONMENTS)) {
            val row = array.optJSONObject(index) ?: continue
            val id = row.optString("id")
            val sealed = row.optString("sealed")
            if (id.isEmpty() || sealed.isEmpty()) continue
            list.add(EnvironmentInfo(id, ForeignText.clean(row.optString("name"), Limits.MAX_NAME).ifEmpty { DEFAULT_NAME }, sealed))
        }
        items = list
        val wanted = json.optString("active")
        active = wanted.takeIf { id -> list.any { it.id == id } }
    }

    private fun write(): Boolean {
        val array = JSONArray()
        items.forEach { array.put(JSONObject().put("id", it.id).put("name", it.name).put("sealed", it.sealed)) }
        val json = JSONObject().put("items", array)
        active?.let { json.put("active", it) }
        val text = json.toString()
        if (damaged || text.toByteArray(Charsets.UTF_8).size > MAX_FILE_BYTES) return false
        return TextFiles.write(file, text)
    }

    companion object {
        const val DEFAULT_NAME = "Environment"
        const val MAX_ENVIRONMENTS = 32
        const val MAX_VARIABLES = 128
        const val MAX_FILE_BYTES = 16 * 1024 * 1024
    }
}

class DraftStore(private val file: File, private val sealer: Sealer) {
    private var lastJson: String? = null

    @Synchronized
    fun save(spec: RequestSpec): Boolean {
        val json = SpecCodec.toJson(spec).toString()
        if (json == lastJson) return true
        val sealed = sealer.seal(json.toByteArray(Charsets.UTF_8)) ?: return false
        if (sealed.length > MAX_FILE_BYTES - 64) return false
        if (!TextFiles.write(file, JSONObject().put("sealed", sealed).toString())) return false
        lastJson = json
        return true
    }

    @Synchronized
    fun load(): RequestSpec? {
        val sealed = SpecCodec.parseObject(TextFiles.read(file, MAX_FILE_BYTES))?.optString("sealed").orEmpty()
        val spec = if (sealed.isEmpty()) null else SpecCodec.open(sealer, sealed)
        if (spec == null && file.exists()) TextFiles.quarantine(file)
        return spec
    }

    companion object {
        const val MAX_FILE_BYTES = 16 * 1024 * 1024
    }
}

data class HistoryEntry(
    val time: Long,
    val method: HttpMethod,
    val url: String,
    val headers: List<Kv>,
    val status: Int,
    val result: String,
    val elapsedMs: Long,
    val sizeBytes: Long,
)

object HistoryEntries {
    private const val MAX_URL = 2_048
    private const val MAX_HEADERS = 32
    private const val MAX_HEADER_VALUE = 256

    fun from(plan: RequestPlan, now: Long, status: Int, result: String, elapsedMs: Long, sizeBytes: Long): HistoryEntry =
        HistoryEntry(
            time = now,
            method = plan.method,
            url = plan.maskedUrl.take(MAX_URL),
            headers = plan.maskedHeaders
                .filterNot { SensitiveNames.isSensitive(it.key) }
                .take(MAX_HEADERS)
                .map { Kv(it.key, it.value.take(MAX_HEADER_VALUE)) },
            status = status,
            result = ForeignText.clean(result, 120),
            elapsedMs = elapsedMs,
            sizeBytes = sizeBytes,
        )

    fun toSpec(entry: HistoryEntry): RequestSpec =
        RequestSpec(method = entry.method, url = entry.url, headers = entry.headers.take(Limits.MAX_ROWS))
}

class HistoryStore(private val file: File, private val sealer: Sealer) {
    private var cache: List<HistoryEntry>? = null
    private var damaged = false

    @Synchronized
    fun list(): List<HistoryEntry> = load()

    @Synchronized
    fun add(entry: HistoryEntry): Boolean {
        val next = (listOf(entry) + load()).take(MAX_ITEMS)
        cache = next
        if (damaged) return false
        val sealed = sealer.seal(encode(next).toByteArray(Charsets.UTF_8)) ?: return false
        if (sealed.length > MAX_FILE_BYTES) return false
        return TextFiles.write(file, sealed)
    }

    @Synchronized
    fun clear(): Boolean {
        cache = emptyList()
        damaged = false
        return TextFiles.delete(file)
    }

    private fun load(): List<HistoryEntry> {
        cache?.let { return it }
        val plain = TextFiles.read(file, MAX_FILE_BYTES)?.let { sealer.open(it.trim()) }
        val array = plain?.let { SpecCodec.parseArray(String(it, Charsets.UTF_8)) }
        if (array == null && file.exists()) damaged = !TextFiles.quarantine(file)
        val items = ArrayList<HistoryEntry>()
        if (array != null) {
            for (index in 0 until minOf(array.length(), MAX_ITEMS)) {
                val row = array.optJSONObject(index) ?: continue
                val url = row.optString("url")
                if (url.isEmpty()) continue
                items.add(
                    HistoryEntry(
                        time = row.optLong("time"),
                        method = HttpMethod.parse(row.optString("method")) ?: HttpMethod.GET,
                        url = url.take(Limits.MAX_URL),
                        headers = SpecCodec.rowsFromJson(row.optJSONArray("headers"))
                            .filterNot { SensitiveNames.isSensitive(it.key) },
                        status = row.optInt("status"),
                        result = ForeignText.clean(row.optString("result"), 120),
                        elapsedMs = row.optLong("elapsed"),
                        sizeBytes = row.optLong("size"),
                    ),
                )
            }
        }
        cache = items
        return items
    }

    private fun encode(items: List<HistoryEntry>): String {
        val array = JSONArray()
        items.forEach {
            array.put(
                JSONObject()
                    .put("time", it.time)
                    .put("method", it.method.name)
                    .put("url", it.url)
                    .put("headers", SpecCodec.rowsToJson(it.headers))
                    .put("status", it.status)
                    .put("result", it.result)
                    .put("elapsed", it.elapsedMs)
                    .put("size", it.sizeBytes),
            )
        }
        return array.toString()
    }

    companion object {
        const val MAX_ITEMS = 200
        const val MAX_FILE_BYTES = 8 * 1024 * 1024
    }
}
