package dev.nekuz.reqlab

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket

class ReviewFixesTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val sealer = TestSealer()
    private val listeners = ArrayList<ServerSocket>()

    @After
    fun closeAll() {
        listeners.forEach { it.close() }
    }

    private fun file(name: String = "data.json") = File(folder.root, name)

    private fun plan(spec: RequestSpec, vars: Map<String, String> = emptyMap()) =
        (RequestBuilder.build(spec, vars) as BuildResult.Ready).plan

    private fun problems(spec: RequestSpec) =
        (RequestBuilder.build(spec, emptyMap()) as BuildResult.Rejected).problems

    @Test
    fun inputLimitJudgesTheResultNotTheInsertion() {
        assertTrue(InputLimits.fits(0, 0, 10, 10))
        assertFalse(InputLimits.fits(0, 0, 11, 10))
        assertTrue(InputLimits.fits(10, 5, 5, 10))
        assertFalse(InputLimits.fits(10, 0, 1, 10))
        assertTrue(InputLimits.fits(10, 10, 0, 10))
    }

    @Test
    fun overflowingTokenGivesOneClearProblem() {
        val found = problems(RequestSpec(url = "https://h.test/", auth = Auth(AuthType.BEARER, token = "x".repeat(Limits.MAX_SECRET + 5))))
        assertEquals(listOf("The bearer token is too long."), found)
    }

    @Test
    fun keyWordSegmentsAreSensitive() {
        listOf(
            "X-Functions-Key", "Ocp-Apim-Subscription-Key", "access_key", "secret.key", "x-key-id", "accessKey", "subscriptionKeys", "keys",
        ).forEach { assertTrue(it, SensitiveNames.isSensitive(it)) }
        listOf("monkey", "keep", "Keep-Alive", "turkey", "Monkey", "hockey", "keyboard").forEach {
            assertFalse(it, SensitiveNames.isSensitive(it))
        }
    }

    @Test
    fun keyHeadersStayOutOfHistoryAndMaskedOutput() {
        val p = plan(
            RequestSpec(
                url = "https://h.test/?access_key=query-secret",
                headers = listOf(Kv("X-Functions-Key", "header-secret"), Kv("Ocp-Apim-Subscription-Key", "sub-secret")),
            ),
        )
        val entry = HistoryEntries.from(p, 1, 200, "ok", 1, 1)
        assertTrue(entry.headers.isEmpty())
        assertFalse(entry.url.contains("query-secret"))
        val preview = PreviewText.of(p)
        val masked = CurlExport.command(p, true)!!
        listOf("query-secret", "header-secret", "sub-secret").forEach {
            assertFalse(preview.contains(it))
            assertFalse(masked.contains(it))
        }
        assertTrue(p.carriesAuth)
    }

    @Test
    fun crossOriginForwardsOnlyTheAllowlist() {
        val headers = listOf(
            Kv("Accept", "a"), Kv("User-Agent", "u"), Kv("Accept-Language", "l"), Kv("X-Unknown", "maybe-secret"),
            Kv("X-Functions-Key", "k"), Kv("Content-Type", "c"), Kv("Host", "h"),
        )
        val decision = Redirects.next(HopRequest(HttpMethod.GET, "https://a.test/", headers, null), 302, "https://b.test/") as RedirectDecision.Follow
        assertEquals(listOf("Accept", "User-Agent", "Accept-Language"), decision.next.headers.map { it.key })
        assertEquals(setOf("X-Unknown", "X-Functions-Key", "Content-Type"), decision.droppedHeaders.toSet())
    }

    @Test
    fun responseViewMasksFinalAndHopUrls() {
        val p = plan(RequestSpec(url = "https://h.test/hook/{{token}}?api_key=abc123&page=1"), mapOf("token" to "pathsecret"))
        val raw = RawResponse(
            200, "OK", emptyList(), ByteArray(0), false, 1, p.url,
            listOf(RedirectHop(302, p.url, "https://h.test/next/pathsecret?token=zz9", emptyList())), emptyList(),
        )
        val view = ResponseViews.build(raw, p::maskUrl)
        assertEquals("https://h.test/hook/***?api_key=***&page=1", view.finalUrl)
        assertFalse(view.hops[0].contains("pathsecret"))
        assertFalse(view.hops[0].contains("abc123"))
        assertFalse(view.hops[0].contains("zz9"))
    }

    @Test
    fun responseViewDefaultStillMasksSensitiveQuery() {
        val raw = RawResponse(200, "OK", emptyList(), ByteArray(0), false, 1, "https://h.test/?token=abc", emptyList(), emptyList())
        assertEquals("https://h.test/?token=***", ResponseViews.build(raw).finalUrl)
    }

    @Test
    fun formSecretsAreMaskedAndChangeTheCurlChoice() {
        val p = plan(
            RequestSpec(
                method = HttpMethod.POST,
                url = "https://h.test/",
                bodyType = BodyType.FORM,
                formFields = listOf(Kv("client_secret", "abc"), Kv("grant_type", "password")),
            ),
        )
        assertTrue(p.secrets.contains("abc"))
        assertEquals("client_secret=***&grant_type=password", p.maskedBody)
        val masked = CurlExport.command(p, true)!!
        val full = CurlExport.command(p, false)!!
        assertFalse(masked.contains("=abc"))
        assertTrue(full.contains("client_secret=abc"))
        assertTrue(masked != full)
        assertTrue(p.carriesAuth)
        assertFalse(PreviewText.of(p).contains("=abc"))
    }

    @Test
    fun formSecretOverHttpWarnsEvenInLiveCheck() {
        val spec = RequestSpec(method = HttpMethod.POST, url = "http://h.test/", bodyType = BodyType.FORM, formFields = listOf(Kv("password", "x")))
        val live = RequestBuilder.build(spec, emptyMap(), includeBody = false) as BuildResult.Ready
        assertTrue(live.plan.warnings.contains(RequestBuilder.CLEARTEXT_AUTH_WARNING))
    }

    @Test
    fun unsafeMethodsStopAcrossOrigins() {
        listOf(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE).forEach { method ->
            val stop = Redirects.next(HopRequest(method, "https://a.test/", emptyList(), ByteArray(0)), 307, "https://b.test/") as RedirectDecision.Stop
            assertTrue(stop.reason, stop.reason.contains(method.name))
        }
        val samePlace = Redirects.next(HopRequest(HttpMethod.DELETE, "https://a.test/", emptyList(), null), 307, "https://a.test/b")
        assertTrue(samePlace is RedirectDecision.Follow)
        assertTrue(Redirects.next(HopRequest(HttpMethod.OPTIONS, "https://a.test/", emptyList(), null), 307, "https://b.test/") is RedirectDecision.Follow)
    }

    @Test
    fun publicServerCannotPointAtPrivateTargets() {
        listOf(
            "http://192.168.1.1/reset", "http://10.0.0.1/", "http://172.16.0.1/", "http://127.0.0.1/", "http://169.254.169.254/latest", "http://100.64.0.1/",
            "http://localhost/", "http://printer.local/", "http://router/", "http://[::1]/", "http://[fe80::1]/", "http://[fd00::1]/",
            "http://[::ffff:192.168.0.1]/", "http://2130706433/", "http://0x7f.1/", "http://0177.0.0.1/", "http://0/",
        ).forEach { target ->
            val decision = Redirects.next(HopRequest(HttpMethod.GET, "https://evil.test/", emptyList(), null), 302, target)
            assertTrue(target, decision is RedirectDecision.Stop && decision.reason.contains("private address"))
        }
    }

    @Test
    fun privateToPrivateRedirectsStillWork() {
        val decision = Redirects.next(HopRequest(HttpMethod.GET, "http://192.168.1.5:8000/", emptyList(), null), 302, "http://192.168.1.6:8001/")
        assertTrue(decision is RedirectDecision.Follow)
        assertTrue(Redirects.next(HopRequest(HttpMethod.GET, "http://localhost:8000/", emptyList(), null), 302, "http://127.0.0.1:8001/") is RedirectDecision.Follow)
    }

    @Test
    fun hostClassification() {
        listOf("8.8.8.8", "example.com", "172.32.0.1", "[2001:db8::1]", "1.2.3.4.5", "a.b").forEach {
            assertFalse(it, HostClass.isPrivate(it))
        }
        assertEquals(0x7F000001L, HostClass.looseIpv4("127.1"))
        assertEquals(0x7F000001L, HostClass.looseIpv4("2130706433"))
        assertNull(HostClass.looseIpv4("example"))
        assertNull(HostClass.looseIpv4("999.1.1.1"))
    }

    @Test
    fun longInputIsRefusedNotCut() {
        assertTrue(problems(RequestSpec(url = "http://h.test/", bodyText = "x".repeat(Limits.MAX_BODY_CHARS + 1))).any { it.contains("longer than") })
        assertTrue(problems(RequestSpec(url = "http://h.test/" + "x".repeat(Limits.MAX_URL))).isNotEmpty())
        assertTrue(problems(RequestSpec(url = "http://h.test/", headers = listOf(Kv("A", "x".repeat(Limits.MAX_HEADER_VALUE + 5))))).isNotEmpty())
        assertTrue(problems(RequestSpec(url = "http://h.test/", auth = Auth(AuthType.BEARER, token = "x".repeat(Limits.MAX_SECRET + 5)))).isNotEmpty())
    }

    @Test
    fun overLimitDraftStaysOverLimitAfterReload() {
        val spec = RequestSpec(
            url = "http://h.test/" + "x".repeat(Limits.MAX_URL + 10),
            headers = listOf(Kv("A", "v".repeat(Limits.MAX_VALUE + 10))),
            bodyText = "b".repeat(Limits.MAX_BODY_CHARS),
        )
        assertEquals(spec, SpecCodec.fromJson(SpecCodec.toJson(spec)))
    }

    @Test
    fun environmentWritesAreCappedAndOldDataSurvives() {
        val store = EnvironmentStore(file(), sealer)
        val big = List(EnvironmentStore.MAX_VARIABLES) { Kv("v$it", "x".repeat(Limits.MAX_STORED_VALUE)) }
        val ids = ArrayList<String>()
        var refused = false
        repeat(EnvironmentStore.MAX_ENVIRONMENTS) {
            val id = store.create("E$it")!!
            ids.add(id)
            if (!store.update(id, "E$it", big)) refused = true
        }
        assertTrue(refused)
        assertTrue(file().length() <= EnvironmentStore.MAX_FILE_BYTES)
        val reloaded = EnvironmentStore(file(), sealer)
        assertEquals(ids.size, reloaded.list().size)
        assertNotNull(reloaded.rows(ids[0]))
        assertFalse(TextFiles.corruptFile(file()).exists())
    }

    @Test
    fun oversizedValueIsRefusedByEnvironmentStore() {
        val store = EnvironmentStore(file(), sealer)
        val id = store.create("E")!!
        assertFalse(store.update(id, "E", listOf(Kv("a", "x".repeat(Limits.MAX_STORED_VALUE + 1)))))
    }

    private fun oversized(target: File) {
        target.outputStream().use { stream ->
            val chunk = ByteArray(1024 * 1024) { 'x'.code.toByte() }
            repeat(17) { stream.write(chunk) }
        }
    }

    @Test
    fun failedReadsNeverLoseTheOldFile() {
        oversized(file("env.json"))
        val env = EnvironmentStore(file("env.json"), sealer)
        assertTrue(env.list().isEmpty())
        assertEquals(17L * 1024 * 1024, TextFiles.corruptFile(file("env.json")).length())
        assertNotNull(env.create("New"))
        assertEquals(17L * 1024 * 1024, TextFiles.corruptFile(file("env.json")).length())

        file("saved.json").writeText("garbage")
        val saved = SavedStore(file("saved.json"), sealer)
        assertEquals(SaveResult.SAVED, saved.save("A", "C", RequestSpec(), 1))
        assertEquals("garbage", TextFiles.corruptFile(file("saved.json")).readText())

        file("history.json").writeText("garbage")
        val history = HistoryStore(file("history.json"), sealer)
        assertTrue(history.list().isEmpty())
        assertEquals("garbage", TextFiles.corruptFile(file("history.json")).readText())
    }

    @Test
    fun undecryptableHistoryIsKeptAside() {
        val first = HistoryStore(file(), sealer)
        first.add(HistoryEntry(1, HttpMethod.GET, "http://a/", emptyList(), 200, "ok", 1, 1))
        val sealedText = file().readText()
        assertTrue(HistoryStore(file(), TestSealer()).list().isEmpty())
        assertEquals(sealedText, TextFiles.corruptFile(file()).readText())
    }

    @Test
    fun clearingHistoryRemovesTheQuarantinedCopyToo() {
        file().writeText("garbage")
        val store = HistoryStore(file(), sealer)
        store.list()
        assertTrue(TextFiles.corruptFile(file()).exists())
        assertTrue(store.clear())
        assertFalse(TextFiles.corruptFile(file()).exists())
        assertFalse(file().exists())
    }

    @Test
    fun historyIsSealedOnDiskIncludingPathSecrets() {
        val spec = RequestSpec(url = "https://hooks.slack.test/services/T000/B000/PathSecretValue")
        val store = HistoryStore(file(), sealer)
        store.add(HistoryEntries.from(plan(spec), 1, 200, "ok", 1, 1))
        val text = file().readText()
        assertFalse(text.contains("PathSecretValue"))
        assertFalse(text.contains("hooks"))
        assertTrue(text.startsWith("v1."))
        assertEquals(spec.url, HistoryStore(file(), sealer).list().single().url)
    }

    @Test
    fun historySealFailureWritesNothing() {
        sealer.failSeal = true
        val store = HistoryStore(file(), sealer)
        assertFalse(store.add(HistoryEntry(1, HttpMethod.GET, "http://a/", emptyList(), 200, "ok", 1, 1)))
        assertFalse(file().exists())
    }

    @Test
    fun shortValuesDoNotCorruptTheUrl() {
        val query = plan(RequestSpec(url = "http://h1.test/v1/items/1?code=1"))
        assertEquals("http://h1.test/v1/items/1?code=***", query.maskedUrl)
        val basic = plan(RequestSpec(url = "https://aaa.test/a/data", auth = Auth(AuthType.BASIC, username = "a", password = "b")))
        assertEquals("https://aaa.test/a/data", basic.maskedUrl)
        assertEquals("https://aaa.test/a/data", HistoryEntries.from(basic, 1, 200, "ok", 1, 1).url)
    }

    @Test
    fun shortSensitiveVariableIsStillMaskedByPosition() {
        val p = plan(
            RequestSpec(url = "http://h1.test/v1/{{token}}/1", headers = listOf(Kv("X-Note", "v1-{{token}}"))),
            mapOf("token" to "1"),
        )
        assertEquals("http://h1.test/v1/***/1", p.maskedUrl)
        assertEquals("v1-***", p.maskedHeaders.single().value)
    }

    @Test
    fun sensitiveVariableAsWholeBaseUrlMasksEverything() {
        val p = plan(RequestSpec(url = "{{secret_url}}"), mapOf("secret_url" to "https://h.test/x"))
        assertEquals(Redactor.MASK, p.maskedUrl)
    }

    @Test
    fun longLiteralSecretsAreNeverMaskedInTheAuthority() {
        assertEquals("https://longsecret.test/***", Redactor.maskUrl("https://longsecret.test/longsecret", listOf("longsecret")))
    }

    @Test
    fun locationIsOmittedWhenCustomHeadersWouldLeak() {
        val spec = RequestSpec(url = "https://h.test/", followRedirects = true)
        val bare = plan(spec)
        assertFalse(CurlExport.omitsLocation(bare))
        assertTrue(CurlExport.command(bare, false)!!.contains("--location"))
        val safe = plan(spec.copy(headers = listOf(Kv("Accept", "*/*"))))
        assertFalse(CurlExport.omitsLocation(safe))
        val custom = plan(spec.copy(headers = listOf(Kv("X-Custom", "1"))))
        assertTrue(CurlExport.omitsLocation(custom))
        assertFalse(CurlExport.command(custom, false)!!.contains("--location"))
        val auth = plan(spec.copy(auth = Auth(AuthType.BEARER, token = "t")))
        assertFalse(CurlExport.command(auth, false)!!.contains("--location"))
        val off = plan(RequestSpec(url = "https://h.test/", headers = listOf(Kv("X-Custom", "1"))))
        assertFalse(CurlExport.omitsLocation(off))
    }

    @Test
    fun chunkingNewlineFreeTextIsFast() {
        val started = System.nanoTime()
        val chunks = BodyChunks.split("x".repeat(6_000_000) + "\n" + "y")
        assertTrue((System.nanoTime() - started) / 1_000_000 < 2_000)
        assertEquals(6_000_000, chunks.dropLast(1).sumOf { it.length })
    }

    @Test
    fun chunkingFindsNewlineOnlyAfterStart() {
        val text = "a\n" + "x".repeat(250)
        val chunks = BodyChunks.split(text, 100)
        assertEquals("a", chunks[0])
        assertEquals(250, chunks.drop(1).sumOf { it.length })
    }

    private fun headerServer(writer: (java.io.OutputStream) -> Unit): Int {
        val listener = ServerSocket(0, 5, InetAddress.getLoopbackAddress())
        listeners.add(listener)
        Thread {
            try {
                listener.accept().use { socket ->
                    val out = socket.getOutputStream()
                    out.write("HTTP/1.1 200 OK\r\n".toByteArray())
                    writer(out)
                }
            } catch (e: IOException) {
                listener.close()
            }
        }.apply { isDaemon = true }.start()
        return listener.localPort
    }

    @Test
    fun endlessFastHeadersAreStoppedWithoutRunningOutOfMemory() {
        val line = ("X-Flood: " + "v".repeat(1000) + "\r\n").toByteArray()
        val port = headerServer { out -> while (true) out.write(line) }
        val started = System.currentTimeMillis()
        val outcome = HttpEngine.execute(
            plan(RequestSpec(url = "http://127.0.0.1:$port/")),
            CancelToken(),
            Timeouts(connectMs = 2_000, readMs = 2_000, totalMs = 20_000, headerMs = 3_000),
        )
        assertTrue(outcome.toString(), outcome is Outcome.Failed || outcome === Outcome.DeadlineExceeded)
        assertTrue(System.currentTimeMillis() - started < 15_000)
    }

    @Test
    fun slowDrippingHeadersHitTheHeaderDeadline() {
        val port = headerServer { out ->
            while (true) {
                out.write("X-Drip: 1\r\n".toByteArray())
                out.flush()
                Thread.sleep(50)
            }
        }
        val started = System.currentTimeMillis()
        val outcome = HttpEngine.execute(
            plan(RequestSpec(url = "http://127.0.0.1:$port/")),
            CancelToken(),
            Timeouts(connectMs = 2_000, readMs = 2_000, totalMs = 20_000, headerMs = 500),
        )
        assertEquals(Outcome.DeadlineExceeded, outcome)
        assertTrue(System.currentTimeMillis() - started < 5_000)
    }

    @Test
    fun heapGrowthGuardTrips() {
        val token = CancelToken()
        assertFalse(token.headerPhaseOverrun(System.nanoTime(), 0))
        token.enterHeaderPhase(System.nanoTime() + 60_000_000_000)
        assertTrue(token.headerPhaseOverrun(System.nanoTime(), Long.MIN_VALUE / 2))
        assertFalse(token.headerPhaseOverrun(System.nanoTime(), Long.MAX_VALUE / 2))
        token.enterHeaderPhase(System.nanoTime() - 1_000)
        assertTrue(token.headerPhaseOverrun(System.nanoTime(), Long.MAX_VALUE / 2))
        token.leaveHeaderPhase()
        assertFalse(token.headerPhaseOverrun(System.nanoTime(), 0))
    }
}
