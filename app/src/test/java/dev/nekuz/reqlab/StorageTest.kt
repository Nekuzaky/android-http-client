package dev.nekuz.reqlab

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class TestSealer(private val key: ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }) : Sealer {
    var failSeal = false
    var failOpen = false

    override fun seal(plain: ByteArray): String? {
        if (failSeal) return null
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return Envelope.encode(iv, cipher.doFinal(plain))
    }

    override fun open(sealed: String): ByteArray? {
        if (failOpen) return null
        val parts = Envelope.decode(sealed) ?: return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, parts.first))
            cipher.doFinal(parts.second)
        } catch (e: AEADBadTagException) {
            null
        }
    }
}

class StorageTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val sealer = TestSealer()
    private val secretSpec = RequestSpec(
        method = HttpMethod.PUT,
        url = "https://h.test/secret-url?token=tok-in-url",
        params = listOf(Kv("p", "param-value")),
        headers = listOf(Kv("Authorization", "Bearer header-secret")),
        auth = Auth(AuthType.BASIC, token = "bearer-secret", username = "user-secret", password = "pass-secret"),
        bodyType = BodyType.JSON,
        bodyText = "{\"body\":\"body-secret\"}",
        formFields = listOf(Kv("f", "form-secret")),
        followRedirects = true,
    )
    private val secretWords = listOf(
        "secret-url", "tok-in-url", "param-value", "header-secret", "bearer-secret", "user-secret", "pass-secret", "body-secret", "form-secret",
    )

    private fun file(name: String = "data.json") = File(folder.root, name)

    private fun assertNoSecrets(file: File) {
        val text = file.readText()
        secretWords.forEach { assertFalse(it, text.contains(it)) }
    }

    @Test
    fun readMissingFileIsNull() {
        assertNull(TextFiles.read(file(), 100))
    }

    @Test
    fun writeThenRead() {
        assertTrue(TextFiles.write(file(), "héllo"))
        assertEquals("héllo", TextFiles.read(file(), 100))
    }

    @Test
    fun writeLeavesNoTempFile() {
        TextFiles.write(file(), "x")
        assertFalse(File(folder.root, "data.json.tmp").exists())
    }

    @Test
    fun readRespectsSizeLimit() {
        TextFiles.write(file(), "x".repeat(1000))
        assertNull(TextFiles.read(file(), 999))
        assertNotNull(TextFiles.read(file(), 1000))
    }

    @Test
    fun readDirectoryIsNull() {
        val directory = folder.newFolder("dir")
        assertNull(TextFiles.read(directory, 100))
    }

    @Test
    fun writeOntoDirectoryFailsWithoutCrash() {
        val directory = folder.newFolder("dir2")
        File(directory, "child").writeText("x")
        assertFalse(TextFiles.write(directory, "text"))
    }

    @Test
    fun staleTempFileDoesNotHurt() {
        File(folder.root, "data.json.tmp").writeText("garbage")
        assertTrue(TextFiles.write(file(), "good"))
        assertEquals("good", TextFiles.read(file(), 100))
    }

    @Test
    fun envelopeRoundTrip() {
        val encoded = Envelope.encode(byteArrayOf(1, 2), byteArrayOf(3))
        val decoded = Envelope.decode(encoded)!!
        assertArrayEquals(byteArrayOf(1, 2), decoded.first)
        assertArrayEquals(byteArrayOf(3), decoded.second)
    }

    @Test
    fun envelopeRejectsMalformed() {
        listOf("", "v1", "v1.", "v1..", "v2.AA==.AA==", "v1.!!!.AA==", "v1.AA==.***", "v1.AA==.AA==.AA==", "plain").forEach {
            assertNull(it, Envelope.decode(it))
        }
    }

    @Test
    fun specRoundTrip() {
        assertEquals(secretSpec, SpecCodec.fromJson(SpecCodec.toJson(secretSpec)))
    }

    @Test
    fun specRoundTripWithHostileText() {
        val text = "\u0000\r\n\"'\\\u2028😀</script>{{x}}"
        val spec = RequestSpec(url = text, headers = listOf(Kv(text, text)), bodyText = text, auth = Auth(AuthType.BEARER, token = text))
        assertEquals(spec, SpecCodec.fromJson(SpecCodec.toJson(spec)))
    }

    @Test
    fun specFromGarbageUsesDefaults() {
        val spec = SpecCodec.fromJson(SpecCodec.parseObject("{\"method\":\"TRACE\",\"authType\":5,\"bodyType\":null,\"params\":\"x\",\"headers\":[1,null,{}]}")!!)
        assertEquals(HttpMethod.GET, spec.method)
        assertEquals(AuthType.NONE, spec.auth.type)
        assertEquals(BodyType.RAW, spec.bodyType)
        assertTrue(spec.params.isEmpty())
        assertEquals(listOf(Kv("", "")), spec.headers)
    }

    @Test
    fun specLoadIsCapped() {
        val spec = RequestSpec(
            url = "u".repeat(Limits.MAX_STORED_VALUE * 2),
            headers = List(Limits.MAX_ROWS * 2) { Kv("k".repeat(Limits.MAX_STORED_KEY * 2), "v".repeat(Limits.MAX_VALUE)) },
            bodyText = "b".repeat(Limits.MAX_STORED_VALUE * 2),
        )
        val loaded = SpecCodec.fromJson(SpecCodec.toJson(spec))
        assertEquals(Limits.MAX_STORED_VALUE, loaded.url.length)
        assertEquals(Limits.MAX_ROWS, loaded.headers.size)
        assertEquals(Limits.MAX_STORED_KEY, loaded.headers[0].key.length)
        assertEquals(Limits.MAX_STORED_VALUE, loaded.bodyText.length)
    }

    @Test
    fun parseToleratesGarbage() {
        listOf("", "{", "[", "nope", "\u0000", "{\"a\":", "[".repeat(100_000), "{\"a\":".repeat(100_000)).forEach {
            assertNull(SpecCodec.parseArray(it + "x"))
            assertNull(SpecCodec.parseObject(it + "x"))
        }
        assertNull(SpecCodec.parseObject(null))
        assertNull(SpecCodec.parseArray(null))
    }

    @Test
    fun savedRequestIsEncryptedOnDisk() {
        val store = SavedStore(file(), sealer)
        assertEquals(SaveResult.SAVED, store.save("My request", "APIs", secretSpec, 1))
        assertNoSecrets(file())
        val text = file().readText()
        assertTrue(text.contains("My request"))
        assertTrue(text.contains("v1."))
    }

    @Test
    fun savedRequestSurvivesReload() {
        SavedStore(file(), sealer).save("Name", "Col", secretSpec, 7)
        val reloaded = SavedStore(file(), sealer)
        val item = reloaded.list().single()
        assertEquals("Name", item.name)
        assertEquals("Col", item.collection)
        assertEquals(HttpMethod.PUT, item.method)
        assertEquals(7, item.updatedAt)
        assertEquals(secretSpec, reloaded.open(item.id))
    }

    @Test
    fun savingSameNameReplaces() {
        val store = SavedStore(file(), sealer)
        store.save("A", "C", RequestSpec(url = "http://1/"), 1)
        val id = store.list().single().id
        store.save("A", "C", RequestSpec(url = "http://2/"), 2)
        val item = store.list().single()
        assertEquals(id, item.id)
        assertEquals("http://2/", store.open(id)!!.url)
        store.save("A", "D", RequestSpec(url = "http://3/"), 3)
        assertEquals(2, store.list().size)
    }

    @Test
    fun blankCollectionBecomesDefaultAndBadNameIsRefused() {
        val store = SavedStore(file(), sealer)
        store.save("A", "  ", RequestSpec(), 1)
        assertEquals(SavedStore.DEFAULT_COLLECTION, store.list().single().collection)
        assertEquals(SaveResult.BAD_NAME, store.save(" \u200B\n", "C", RequestSpec(), 1))
        assertEquals(1, store.list().size)
    }

    @Test
    fun namesAreCleaned() {
        val store = SavedStore(file(), sealer)
        store.save("a\u202Eb\r\nc", "d\u0000e", RequestSpec(), 1)
        val item = store.list().single()
        assertEquals("abc", item.name)
        assertEquals("de", item.collection)
    }

    @Test
    fun sealFailureNeverWritesPlaintext() {
        sealer.failSeal = true
        val store = SavedStore(file(), sealer)
        assertEquals(SaveResult.SEAL_FAILED, store.save("A", "C", secretSpec, 1))
        assertFalse(file().exists())
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun wrongKeyOrTamperingOpensNothing() {
        val store = SavedStore(file(), sealer)
        store.save("A", "C", secretSpec, 1)
        val id = store.list().single().id
        assertNull(SavedStore(file(), TestSealer()).open(id))
        val tampered = file().readText().replace("v1.", "v1.AAAA")
        file().writeText(tampered)
        assertNull(SavedStore(file(), sealer).open(id))
    }

    @Test
    fun corruptSavedFilesGiveEmptyList() {
        listOf("", "{", "not json", "{}", "[1,2,null]", "[{\"id\":\"x\"}]", "\u0000\u0001", "[".repeat(100_000)).forEach {
            file().writeText(it)
            assertTrue(it.take(16), SavedStore(file(), sealer).list().isEmpty())
        }
    }

    @Test
    fun corruptFileIsRecoveredOnNextSave() {
        file().writeText("garbage")
        val store = SavedStore(file(), sealer)
        assertEquals(SaveResult.SAVED, store.save("A", "C", RequestSpec(), 1))
        assertEquals(1, SavedStore(file(), sealer).list().size)
    }

    @Test
    fun oversizedFileIsIgnored() {
        file().outputStream().use { stream ->
            val chunk = ByteArray(1024 * 1024) { 'x'.code.toByte() }
            repeat(17) { stream.write(chunk) }
        }
        assertTrue(SavedStore(file(), sealer).list().isEmpty())
    }

    @Test
    fun storeLimitIsEnforced() {
        val store = SavedStore(file(), sealer)
        repeat(SavedStore.MAX_ITEMS) { store.save("n$it", "C", RequestSpec(), 1) }
        assertEquals(SaveResult.FULL, store.save("over", "C", RequestSpec(), 1))
        assertEquals(SaveResult.SAVED, store.save("n1", "C", RequestSpec(url = "http://x/"), 2))
    }

    @Test
    fun deleteAndDeleteCollection() {
        val store = SavedStore(file(), sealer)
        store.save("a", "C1", RequestSpec(), 1)
        store.save("b", "C1", RequestSpec(), 1)
        store.save("c", "C2", RequestSpec(), 1)
        assertTrue(store.delete(store.list().first { it.name == "a" }.id))
        assertEquals(2, store.list().size)
        assertTrue(store.delete("missing"))
        assertTrue(store.deleteCollection("C1"))
        assertEquals(listOf("c"), SavedStore(file(), sealer).list().map { it.name })
    }

    @Test
    fun openUnknownIdIsNull() {
        assertNull(SavedStore(file(), sealer).open("nope"))
    }

    @Test
    fun environmentValuesAreEncrypted() {
        val store = EnvironmentStore(file(), sealer)
        val id = store.create("Staging")!!
        assertTrue(store.update(id, "Staging", listOf(Kv("base_url", "http://var-secret"), Kv("token", "tok-secret"))))
        val text = file().readText()
        assertFalse(text.contains("var-secret"))
        assertFalse(text.contains("tok-secret"))
        assertFalse(text.contains("base_url"))
        assertTrue(text.contains("Staging"))
    }

    @Test
    fun environmentReloadAndActive() {
        val store = EnvironmentStore(file(), sealer)
        val id = store.create("E1")!!
        store.update(id, "E2", listOf(Kv("a", "1"), Kv("bad name", "2")))
        assertTrue(store.activeVariables().isEmpty())
        assertTrue(store.setActive(id))
        val reloaded = EnvironmentStore(file(), sealer)
        assertEquals(id, reloaded.activeId())
        assertEquals("E2", reloaded.list().single().name)
        assertEquals(mapOf("a" to "1"), reloaded.activeVariables())
        assertTrue(reloaded.setActive(null))
        assertNull(EnvironmentStore(file(), sealer).activeId())
    }

    @Test
    fun activeOfUnknownIdIsCleared() {
        val store = EnvironmentStore(file(), sealer)
        store.setActive("nope")
        assertNull(store.activeId())
    }

    @Test
    fun deletingActiveEnvironmentClearsIt() {
        val store = EnvironmentStore(file(), sealer)
        val id = store.create("E")!!
        store.setActive(id)
        assertTrue(store.delete(id))
        assertNull(store.activeId())
        assertTrue(store.activeVariables().isEmpty())
        assertFalse(store.update(id, "E", emptyList()))
    }

    @Test
    fun environmentFailuresKeepOldState() {
        val store = EnvironmentStore(file(), sealer)
        val id = store.create("E")!!
        store.update(id, "E", listOf(Kv("a", "1")))
        sealer.failSeal = true
        assertFalse(store.update(id, "New", listOf(Kv("a", "2"))))
        assertNull(store.create("X"))
        sealer.failSeal = false
        assertEquals("E", store.find(id)!!.name)
        assertEquals(listOf(Kv("a", "1")), store.rows(id))
    }

    @Test
    fun environmentDecryptFailureIsNullNotCrash() {
        val store = EnvironmentStore(file(), sealer)
        val id = store.create("E")!!
        store.setActive(id)
        sealer.failOpen = true
        assertNull(store.rows(id))
        assertTrue(store.activeVariables().isEmpty())
    }

    @Test
    fun corruptEnvironmentFiles() {
        listOf("", "[]", "{", "{\"items\":5}", "{\"items\":[1,{}],\"active\":\"z\"}", "x").forEach {
            file().writeText(it)
            val store = EnvironmentStore(file(), sealer)
            assertTrue(store.list().isEmpty())
            assertNull(store.activeId())
        }
    }

    @Test
    fun environmentLimit() {
        val store = EnvironmentStore(file(), sealer)
        repeat(EnvironmentStore.MAX_ENVIRONMENTS) { assertNotNull(store.create("E$it")) }
        assertNull(store.create("over"))
    }

    @Test
    fun draftIsEncryptedAndRestored() {
        val store = DraftStore(file(), sealer)
        assertTrue(store.save(secretSpec))
        assertNoSecrets(file())
        assertEquals(secretSpec, DraftStore(file(), sealer).load())
    }

    @Test
    fun draftSkipsIdenticalWrite() {
        val store = DraftStore(file(), sealer)
        store.save(secretSpec)
        val first = file().readText()
        store.save(secretSpec)
        assertEquals(first, file().readText())
        store.save(secretSpec.copy(url = "http://other/"))
        assertNotEquals(first, file().readText())
    }

    @Test
    fun draftSealFailureKeepsNoPlaintext() {
        sealer.failSeal = true
        assertFalse(DraftStore(file(), sealer).save(secretSpec))
        assertFalse(file().exists())
    }

    @Test
    fun corruptDraftIsNull() {
        listOf("", "{", "{}", "{\"sealed\":\"junk\"}", "{\"sealed\":5}", "[]").forEach {
            file().writeText(it)
            assertNull(it, DraftStore(file(), sealer).load())
        }
    }

    private fun plan(spec: RequestSpec, vars: Map<String, String> = emptyMap()) =
        (RequestBuilder.build(spec, vars) as BuildResult.Ready).plan

    @Test
    fun historyHoldsNoAuthValues() {
        val spec = RequestSpec(
            method = HttpMethod.POST,
            url = "http://h.test/{{my_token}}/x?password=url-pass&page=2",
            params = listOf(Kv("api_key", "key-value"), Kv("q", "keep-q")),
            headers = listOf(
                Kv("Authorization", "Bearer manual-auth"),
                Kv("Cookie", "sid=cookie-value"),
                Kv("Proxy-Authorization", "proxy-value"),
                Kv("X-Api-Key", "api-header"),
                Kv("X-Echo", "{{my_token}} and bearer-token and keep-h"),
            ),
            auth = Auth(AuthType.BEARER, token = "bearer-token"),
            bodyText = "body-text",
        )
        val entry = HistoryEntries.from(plan(spec, mapOf("my_token" to "var-token")), 1, 200, "200 OK", 5, 10)
        val store = HistoryStore(file(), sealer)
        store.add(entry)
        val text = entry.toString() + HistoryStore(file(), sealer).list().single().toString()
        assertTrue(file().readText().startsWith("v1."))
        listOf(
            "url-pass", "key-value", "manual-auth", "cookie-value", "proxy-value", "api-header", "var-token", "bearer-token",
            "body-text", "Authorization", "Cookie", "X-Api-Key",
        ).forEach { assertFalse(it, text.contains(it)) }
        listOf("keep-q", "keep-h", "page=2", "X-Echo").forEach { assertTrue(it, text.contains(it)) }
    }

    @Test
    fun historyHoldsNoBasicCredentials() {
        val spec = RequestSpec(url = "https://h.test/user1/pass1", auth = Auth(AuthType.BASIC, username = "user1", password = "pass1"))
        val entry = HistoryEntries.from(plan(spec), 1, 200, "200", 5, 10)
        assertEquals("https://h.test/***/***", entry.url)
        assertTrue(entry.headers.isEmpty())
    }

    @Test
    fun historyReloadFiltersSensitiveHeadersFromOldFiles() {
        val plainHistory = ("[{\"time\":1,\"method\":\"GET\",\"url\":\"http://h/\",\"headers\":[{\"k\":\"Cookie\",\"v\":\"x\"},{\"k\":\"Accept\",\"v\":\"y\"}]}]")
        file().writeText(sealer.seal(plainHistory.toByteArray())!!)
        val entry = HistoryStore(file(), sealer).list().single()
        assertEquals(listOf(Kv("Accept", "y")), entry.headers)
    }

    @Test
    fun historyRoundTripAndOrder() {
        val store = HistoryStore(file(), sealer)
        store.add(HistoryEntry(1, HttpMethod.GET, "http://a/", listOf(Kv("A", "b")), 200, "200 OK", 3, 4))
        store.add(HistoryEntry(2, HttpMethod.PUT, "http://b/", emptyList(), 0, "Failed", 0, 0))
        val list = HistoryStore(file(), sealer).list()
        assertEquals(listOf("http://b/", "http://a/"), list.map { it.url })
        assertEquals(HistoryEntry(1, HttpMethod.GET, "http://a/", listOf(Kv("A", "b")), 200, "200 OK", 3, 4), list[1])
    }

    @Test
    fun historyIsBounded() {
        val store = HistoryStore(file(), sealer)
        repeat(HistoryStore.MAX_ITEMS + 25) { store.add(HistoryEntry(it.toLong(), HttpMethod.GET, "http://a/$it", emptyList(), 200, "ok", 1, 1)) }
        val list = HistoryStore(file(), sealer).list()
        assertEquals(HistoryStore.MAX_ITEMS, list.size)
        assertEquals("http://a/${HistoryStore.MAX_ITEMS + 24}", list[0].url)
    }

    @Test
    fun clearHistoryRemovesTheFile() {
        val store = HistoryStore(file(), sealer)
        store.add(HistoryEntry(1, HttpMethod.GET, "http://a/", emptyList(), 200, "ok", 1, 1))
        assertTrue(file().exists())
        assertTrue(store.clear())
        assertFalse(file().exists())
        assertTrue(store.list().isEmpty())
        assertTrue(store.clear())
    }

    @Test
    fun corruptHistoryIsEmpty() {
        listOf("", "{}", "[", "x", "[null,1,{}]", "[{\"url\":\"\"}]").forEach {
            file().writeText(it)
            assertTrue(it, HistoryStore(file(), sealer).list().isEmpty())
        }
    }

    @Test
    fun historyToSpecCarriesNoAuth() {
        val spec = HistoryEntries.toSpec(HistoryEntry(1, HttpMethod.DELETE, "http://a/?token=***", listOf(Kv("A", "b")), 200, "ok", 1, 1))
        assertEquals(HttpMethod.DELETE, spec.method)
        assertEquals(AuthType.NONE, spec.auth.type)
        assertEquals("", spec.bodyText)
    }

    @Test
    fun historyEntryIsBounded() {
        val spec = RequestSpec(url = "http://h/" + "x".repeat(Limits.MAX_URL - 100), headers = List(60) { Kv("H$it", "v".repeat(5000)) })
        val entry = HistoryEntries.from(plan(spec), 1, 200, "r".repeat(1000), 1, 1)
        assertTrue(entry.url.length <= 2048)
        assertEquals(32, entry.headers.size)
        assertTrue(entry.headers.all { it.value.length <= 256 })
        assertTrue(entry.result.length <= 121)
    }
}
