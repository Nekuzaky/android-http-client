package dev.nekuz.reqlab

import java.util.Base64
import java.util.Locale

class RequestPlan(
    val method: HttpMethod,
    val url: String,
    val headers: List<Kv>,
    val body: ByteArray?,
    val followRedirects: Boolean,
    val warnings: List<String>,
    val usedVariables: Set<String>,
    val secrets: List<String>,
    val carriesAuth: Boolean,
    val cleartextAuth: Boolean,
    val maskedUrl: String = url,
    val maskedHeaders: List<Kv> = headers,
    val maskedBody: String = "",
) {
    fun maskUrl(other: String): String = if (other == url) maskedUrl else Redactor.maskUrl(other, secrets)
}

sealed class BuildResult {
    data class Ready(val plan: RequestPlan) : BuildResult()

    data class Rejected(val problems: List<String>, val warnings: List<String>) : BuildResult()
}

object RequestBuilder {
    const val CLEARTEXT_AUTH_WARNING =
        "Warning: this request sends credentials over unencrypted http://. Anyone on the network can read them."
    private const val AUTHORIZATION = "Authorization"
    private const val CONTENT_TYPE = "Content-Type"
    private const val SENTINEL = "RQLBMASKEDVALUEQZX"

    private class Context(val vars: Map<String, String>) {
        val problems = ArrayList<String>()
        val warnings = ArrayList<String>()
        val used = LinkedHashSet<String>()
        val secrets = LinkedHashSet<String>()
        val secretVariables = LinkedHashSet<String>()
        val headerTemplates = HashMap<Kv, String>()

        fun strict(template: String, where: String, maxLength: Int = Limits.MAX_RESOLVED): Substituted {
            val result = Variables.substitute(template, vars, maxLength)
            used.addAll(result.used)
            result.unknown.forEach { problems.add("Unknown variable {{${ForeignText.clean(it, 64)}}} in $where.") }
            if (result.overflow) problems.add(where.replaceFirstChar { it.uppercase(Locale.ROOT) } + " is too long.")
            return result
        }

        fun secretValue(value: String) {
            if (value.isEmpty()) return
            secrets.add(value)
            secrets.add(Urls.encodeComponent(value))
            secrets.add(Urls.encodeForm(value))
        }
    }

    fun build(spec: RequestSpec, vars: Map<String, String>, includeBody: Boolean = true): BuildResult {
        val context = Context(vars)
        val url = buildUrl(spec, context)
        val headers = buildHeaders(spec, context)
        val authHeader = authHeader(spec.auth, context)
        if (authHeader != null) {
            if (headers.any { it.key.equals(AUTHORIZATION, ignoreCase = true) }) {
                context.warnings.add("The Auth section replaces the Authorization header from the headers list.")
                headers.removeAll { it.key.equals(AUTHORIZATION, ignoreCase = true) }
            }
            headers.add(Kv(AUTHORIZATION, authHeader))
        }
        if (spec.bodyText.length > Limits.MAX_BODY_CHARS) {
            context.problems.add("The body is longer than ${Limits.MAX_BODY_CHARS} characters, so nothing was cut and the request is refused.")
        }
        val body = if (includeBody) buildBody(spec, context) else null
        if (body != null && body.isNotEmpty() && headers.none { it.key.equals(CONTENT_TYPE, ignoreCase = true) }) {
            headers.add(Kv(CONTENT_TYPE, defaultContentType(spec.bodyType)))
        }
        vars.forEach { (name, value) ->
            if (name in context.used && (SensitiveNames.isSensitive(name) || name in context.secretVariables)) {
                context.secretValue(value)
            }
        }
        val queryValues = url?.let { Redactor.queryValues(it.toString()) }.orEmpty()
        context.secrets.addAll(queryValues)
        val carriesAuth = spec.auth.type != AuthType.NONE ||
            headers.any { SensitiveNames.isSensitive(it.key) } ||
            queryValues.isNotEmpty() ||
            formCarriesSecret(spec, vars)
        val cleartextAuth = carriesAuth && url?.isCleartext == true
        if (cleartextAuth) context.warnings.add(0, CLEARTEXT_AUTH_WARNING)
        if (context.problems.isNotEmpty() || url == null) {
            return BuildResult.Rejected(context.problems.distinct(), context.warnings.distinct())
        }
        val secrets = context.secrets.filter { it.isNotEmpty() }
        val maskedVars = vars.mapValues { (name, value) ->
            if (name in context.used && (SensitiveNames.isSensitive(name) || name in context.secretVariables)) SENTINEL else value
        }
        return BuildResult.Ready(
            RequestPlan(
                method = spec.method,
                url = url.toString(),
                headers = headers,
                body = body,
                followRedirects = spec.followRedirects,
                warnings = context.warnings.distinct(),
                usedVariables = context.used,
                secrets = secrets,
                carriesAuth = carriesAuth,
                cleartextAuth = cleartextAuth,
                maskedUrl = maskedUrl(spec, maskedVars, secrets),
                maskedHeaders = maskedHeaders(headers, context, maskedVars, secrets),
                maskedBody = if (body != null) maskedBody(spec, vars, maskedVars, secrets) else "",
            ),
        )
    }

    private fun unseal(text: String, secrets: List<String>): String =
        Redactor.mask(text.replace(SENTINEL, Redactor.MASK, ignoreCase = true), secrets)

    private fun maskedUrl(spec: RequestSpec, maskedVars: Map<String, String>, secrets: List<String>): String {
        val resolved = Variables.substitute(spec.url, maskedVars, Limits.MAX_RESOLVED).text
        val parsed = (Urls.parse(resolved) as? UrlResult.Ok)?.url ?: return Redactor.MASK
        val query = spec.params.filterNot { it.isBlank }.joinToString("&") { row ->
            val name = Variables.substitute(row.key, maskedVars).text
            val value = if (SensitiveNames.isSensitive(name)) SENTINEL else Variables.substitute(row.value, maskedVars).text
            Urls.encodeComponent(name) + "=" + Urls.encodeComponent(value)
        }
        val text = parsed.withExtraQuery(query).toString().replace(SENTINEL, Redactor.MASK, ignoreCase = true)
        return Redactor.maskUrl(text, secrets)
    }

    private fun maskedHeaders(
        headers: List<Kv>,
        context: Context,
        maskedVars: Map<String, String>,
        secrets: List<String>,
    ): List<Kv> = headers.map { header ->
        val template = context.headerTemplates[header]
        when {
            SensitiveNames.isSensitive(header.key) -> Kv(header.key, Redactor.MASK)
            template != null -> Kv(header.key, unseal(Variables.substitute(template, maskedVars).text.trim(' '), secrets))
            else -> Kv(header.key, Redactor.mask(header.value, secrets))
        }
    }

    private fun maskedBody(
        spec: RequestSpec,
        vars: Map<String, String>,
        maskedVars: Map<String, String>,
        secrets: List<String>,
    ): String {
        if (spec.bodyType != BodyType.FORM) return unseal(Variables.substitute(spec.bodyText, maskedVars).text, secrets)
        val text = spec.formFields.filterNot { it.isBlank }.joinToString("&") { row ->
            val realName = Variables.substitute(row.key, vars).text
            val value = if (SensitiveNames.isSensitive(realName)) SENTINEL else Variables.substitute(row.value, maskedVars).text
            Urls.encodeForm(Variables.substitute(row.key, maskedVars).text) + "=" + Urls.encodeForm(value)
        }
        return unseal(text, secrets)
    }

    private fun formCarriesSecret(spec: RequestSpec, vars: Map<String, String>): Boolean =
        spec.bodyType == BodyType.FORM && spec.method.allowsBody && spec.formFields.any {
            !it.isBlank && SensitiveNames.isSensitive(Variables.substitute(it.key, vars).text)
        }

    private fun buildUrl(spec: RequestSpec, context: Context): ParsedUrl? {
        val resolved = context.strict(spec.url, "the URL", Limits.MAX_URL + 1)
        var url: ParsedUrl? = null
        if (resolved.unknown.isEmpty() && !resolved.overflow) {
            when (val parsed = Urls.parse(resolved.text)) {
                is UrlResult.Ok -> url = parsed.url
                is UrlResult.Bad -> context.problems.add(parsed.reason)
            }
        }
        val query = ArrayList<String>()
        spec.params.filterNot { it.isBlank }.forEach { row ->
            val name = context.strict(row.key, "a query parameter name").text
            val value = context.strict(row.value, "a query parameter value")
            if (name.isBlank()) {
                context.problems.add("A query parameter has a value but no name.")
            } else {
                if (SensitiveNames.isSensitive(name)) {
                    context.secretVariables.addAll(value.used)
                    context.secretValue(value.text)
                }
                query.add(Urls.encodeComponent(name) + "=" + Urls.encodeComponent(value.text))
            }
        }
        val full = url?.withExtraQuery(query.joinToString("&"))
        if (full != null && full.toString().length > Limits.MAX_URL) context.problems.add("The URL is too long.")
        return full
    }

    private fun buildHeaders(spec: RequestSpec, context: Context): MutableList<Kv> {
        val headers = ArrayList<Kv>()
        spec.headers.filterNot { it.isBlank }.forEach { row ->
            val name = context.strict(row.key, "a header name", Limits.MAX_KEY + 1).text.trim(' ')
            val resolved = context.strict(row.value, "a header value", Limits.MAX_HEADER_VALUE + 1)
            val value = resolved.text.trim(' ')
            val nameProblem = HeaderRules.nameProblem(name)
            val valueProblem = HeaderRules.valueProblem(name, value)
            if (nameProblem != null) context.problems.add(nameProblem)
            if (valueProblem != null) context.problems.add(valueProblem)
            if (nameProblem == null && valueProblem == null) {
                val header = Kv(name, value)
                headers.add(header)
                context.headerTemplates[header] = row.value
                if (SensitiveNames.isSensitive(name)) {
                    context.secretVariables.addAll(resolved.used)
                    addHeaderSecrets(context, value)
                }
            }
        }
        return headers
    }

    private fun addHeaderSecrets(context: Context, value: String) {
        if (value.isEmpty()) return
        context.secrets.add(value)
        val space = value.indexOf(' ')
        if (space in 1 until value.lastIndex) context.secretValue(value.substring(space + 1))
    }

    private fun authHeader(auth: Auth, context: Context): String? {
        when (auth.type) {
            AuthType.NONE -> return null
            AuthType.BEARER -> {
                val resolved = context.strict(auth.token, "the bearer token", Limits.MAX_SECRET + 1)
                context.secretVariables.addAll(resolved.used)
                if (resolved.overflow) return null
                val token = resolved.text.trim(' ')
                if (token.isEmpty()) {
                    context.problems.add("Enter a bearer token or choose no auth.")
                    return null
                }
                val problem = HeaderRules.valueProblem(AUTHORIZATION, token)
                if (problem != null) {
                    context.problems.add(problem)
                    return null
                }
                context.secretValue(token)
                return "Bearer $token"
            }
            AuthType.BASIC -> {
                val user = context.strict(auth.username, "the username", Limits.MAX_SECRET + 1)
                val pass = context.strict(auth.password, "the password", Limits.MAX_SECRET + 1)
                context.secretVariables.addAll(user.used)
                context.secretVariables.addAll(pass.used)
                if (user.overflow || pass.overflow) return null
                val username = user.text
                val password = pass.text
                if (username.isEmpty() && password.isEmpty()) {
                    context.problems.add("Enter a username and password or choose no auth.")
                    return null
                }
                if (username.contains(':')) {
                    context.problems.add("A basic auth username cannot contain a colon.")
                    return null
                }
                if (HeaderRules.hasLineBreak(username) || HeaderRules.hasLineBreak(password)) {
                    context.problems.add("The username or password contains a line break, so the request is refused.")
                    return null
                }
                if ((username + password).any { it.code < 0x20 || it.code == 0x7F }) {
                    context.problems.add("The username or password contains a control character.")
                    return null
                }
                context.secretValue(username)
                context.secretValue(password)
                val encoded = Base64.getEncoder().encodeToString("$username:$password".toByteArray(Charsets.UTF_8))
                context.secrets.add(encoded)
                return "Basic $encoded"
            }
        }
    }

    private fun buildBody(spec: RequestSpec, context: Context): ByteArray? {
        val hasContent = when (spec.bodyType) {
            BodyType.FORM -> spec.formFields.any { !it.isBlank }
            else -> spec.bodyText.isNotEmpty()
        }
        if (!spec.method.allowsBody) {
            if (hasContent) context.warnings.add("${spec.method.name} requests do not send a body here. The body is ignored.")
            return null
        }
        fun soft(template: String, where: String): Substituted {
            val result = Variables.substitute(template, context.vars)
            context.used.addAll(result.used)
            result.unknown.forEach {
                context.warnings.add("Unknown variable {{${ForeignText.clean(it, 64)}}} left as text in $where.")
            }
            if (result.overflow) context.problems.add("The body is too large after substitution.")
            return result
        }
        val text = when (spec.bodyType) {
            BodyType.FORM -> {
                val parts = ArrayList<String>()
                spec.formFields.filterNot { it.isBlank }.forEach { row ->
                    val name = soft(row.key, "a form field").text
                    val value = soft(row.value, "a form field")
                    if (name.isBlank()) {
                        context.problems.add("A form field has a value but no name.")
                    } else {
                        if (SensitiveNames.isSensitive(name)) {
                            context.secretVariables.addAll(value.used)
                            context.secretValue(value.text)
                        }
                        parts.add(Urls.encodeForm(name) + "=" + Urls.encodeForm(value.text))
                    }
                }
                parts.joinToString("&")
            }
            BodyType.JSON -> {
                val resolved = soft(spec.bodyText, "the body").text
                if (resolved.isNotEmpty() && !JsonPretty.isValid(resolved)) {
                    context.warnings.add("The JSON body does not parse. It will be sent exactly as written.")
                }
                resolved
            }
            BodyType.RAW -> soft(spec.bodyText, "the body").text
        }
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size > Limits.MAX_BODY_BYTES) context.problems.add("The body is larger than 2 MiB.")
        return bytes
    }

    private fun defaultContentType(type: BodyType) = when (type) {
        BodyType.RAW -> "text/plain; charset=utf-8"
        BodyType.JSON -> "application/json"
        BodyType.FORM -> "application/x-www-form-urlencoded"
    }
}

object PreviewText {
    private const val MAX_BODY = 4_096
    private const val MAX_TOTAL = 16_384

    fun of(plan: RequestPlan): String {
        val text = StringBuilder()
        if (plan.warnings.isNotEmpty()) {
            plan.warnings.forEach { text.append(it).append('\n') }
            text.append('\n')
        }
        text.append(plan.method.name).append(' ').append(plan.maskedUrl).append('\n')
        if (plan.usedVariables.isNotEmpty()) {
            text.append("\nVariables used: ").append(plan.usedVariables.joinToString(", ")).append('\n')
        }
        if (plan.maskedHeaders.isNotEmpty()) {
            text.append("\nHeaders\n")
            plan.maskedHeaders.forEach { header ->
                text.append(header.key).append(": ").append(header.value).append('\n')
            }
        }
        val body = plan.body
        if (body != null && body.isNotEmpty()) {
            text.append("\nBody, ").append(Text.size(body.size.toLong())).append('\n')
            text.append(plan.maskedBody, 0, minOf(plan.maskedBody.length, MAX_BODY))
            if (plan.maskedBody.length > MAX_BODY) text.append("\n(preview cut)")
            text.append('\n')
        }
        if (plan.followRedirects) text.append("\nRedirects will be followed.\n")
        return ForeignText.block(text, MAX_TOTAL)
    }
}

object Text {
    fun size(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(Locale.ROOT, "%.1f KiB", bytes / 1024.0)
        else -> String.format(Locale.ROOT, "%.2f MiB", bytes / (1024.0 * 1024.0))
    }

    fun millis(value: Long): String = when {
        value < 1000 -> "$value ms"
        else -> String.format(Locale.ROOT, "%.2f s", value / 1000.0)
    }
}
