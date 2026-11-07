package dev.nekuz.reqlab

enum class HttpMethod {
    GET, POST, PUT, PATCH, DELETE, HEAD, OPTIONS;

    val allowsBody: Boolean
        get() = this == POST || this == PUT || this == PATCH || this == DELETE

    companion object {
        fun parse(name: String?): HttpMethod? = entries.firstOrNull { it.name == name }
    }
}

enum class BodyType {
    RAW, JSON, FORM;

    companion object {
        fun parse(name: String?): BodyType? = entries.firstOrNull { it.name == name }
    }
}

enum class AuthType {
    NONE, BEARER, BASIC;

    companion object {
        fun parse(name: String?): AuthType? = entries.firstOrNull { it.name == name }
    }
}

data class Kv(val key: String, val value: String) {
    val isBlank: Boolean
        get() = key.isBlank() && value.isBlank()
}

data class Auth(
    val type: AuthType = AuthType.NONE,
    val token: String = "",
    val username: String = "",
    val password: String = "",
) {
    override fun toString() = "Auth($type)"
}

data class RequestSpec(
    val method: HttpMethod = HttpMethod.GET,
    val url: String = "",
    val params: List<Kv> = emptyList(),
    val headers: List<Kv> = emptyList(),
    val auth: Auth = Auth(),
    val bodyType: BodyType = BodyType.RAW,
    val bodyText: String = "",
    val formFields: List<Kv> = emptyList(),
    val followRedirects: Boolean = false,
)

object InputLimits {
    fun fits(currentLength: Int, replacedLength: Int, insertedLength: Int, maxLength: Int): Boolean =
        currentLength - replacedLength + insertedLength <= maxLength
}

object Limits {
    const val MAX_URL = 8_192
    const val MAX_ROWS = 64
    const val MAX_KEY = 256
    const val MAX_VALUE = 8_192
    const val MAX_HEADER_VALUE = 8_192
    const val MAX_SECRET = 8_192
    const val MAX_BODY_CHARS = 64_000
    const val MAX_BODY_BYTES = 2_097_152
    const val MAX_RESOLVED = 2_097_152
    const val RESPONSE_CAP = 2_097_152
    const val MAX_RESPONSE_HEADERS = 200
    const val MAX_REDIRECTS = 5
    const val CONNECT_TIMEOUT_MS = 10_000
    const val READ_TIMEOUT_MS = 30_000
    const val TOTAL_TIMEOUT_MS = 120_000L
    const val MAX_NAME = 80
    const val MAX_COPY_CHARS = 256_000
    const val MAX_STORED_KEY = 4_096
    const val MAX_STORED_VALUE = MAX_BODY_CHARS
    const val MAX_HEADER_HEAP_GROWTH = 64L * 1024 * 1024
}
