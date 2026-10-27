package ye.mishwari.ai

import android.content.Context
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

private data class FakeHttpRequest(val path: String, val headers: Map<String, String>, val body: String)
private data class FakeHttpResponse(
    val status: Int = 200,
    val headers: Map<String, String> = emptyMap(),
    val body: String,
)

/** One-request loopback HTTP server; used only with the provider's internal test-only HTTP seam. */
private class FakeHttpServer(private val handler: (FakeHttpRequest) -> FakeHttpResponse) : AutoCloseable {
    private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    val baseUrl = "http://127.0.0.1:${server.localPort}"
    private val worker = Thread({ serveOneRequest() }, "assistant-fake-http").apply {
        isDaemon = true
        start()
    }

    private fun serveOneRequest() {
        try {
            server.accept().use { socket ->
                socket.soTimeout = 2_000
                val input = BufferedInputStream(socket.getInputStream())
                val requestLine = readAsciiLine(input) ?: return
                val headers = linkedMapOf<String, String>()
                while (true) {
                    val line = readAsciiLine(input) ?: return
                    if (line.isEmpty()) break
                    val colon = line.indexOf(':')
                    if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
                }
                val length = headers["content-length"]?.toIntOrNull() ?: 0
                val bodyBytes = ByteArray(length)
                var offset = 0
                while (offset < length) {
                    val count = input.read(bodyBytes, offset, length - offset)
                    if (count < 0) return
                    offset += count
                }
                val path = requestLine.split(' ').getOrNull(1).orEmpty()
                val response = handler(FakeHttpRequest(path, headers, String(bodyBytes, StandardCharsets.UTF_8)))
                val responseBytes = response.body.toByteArray(StandardCharsets.UTF_8)
                val statusText = when (response.status) {
                    200 -> "OK"
                    302 -> "Found"
                    401 -> "Unauthorized"
                    403 -> "Forbidden"
                    429 -> "Too Many Requests"
                    else -> "Test Response"
                }
                val responseHeaders = buildString {
                    append("HTTP/1.1 ${response.status} $statusText\r\n")
                    append("Content-Length: ${responseBytes.size}\r\n")
                    append("Connection: close\r\n")
                    response.headers.forEach { (name, value) -> append("$name: $value\r\n") }
                    append("\r\n")
                }.toByteArray(StandardCharsets.ISO_8859_1)
                runCatching {
                    socket.getOutputStream().apply {
                        write(responseHeaders)
                        write(responseBytes)
                        flush()
                    }
                }
            }
        } catch (_: Exception) {
            // Client timeout/close is expected in timeout and redirect tests.
        }
    }

    private fun readAsciiLine(input: BufferedInputStream): String? {
        val output = ByteArrayOutputStream()
        var previous = -1
        while (true) {
            val next = input.read()
            if (next < 0) return if (output.size() == 0) null else output.toString("US-ASCII")
            if (previous == '\r'.code && next == '\n'.code) {
                val bytes = output.toByteArray()
                return String(bytes, 0, (bytes.size - 1).coerceAtLeast(0), StandardCharsets.US_ASCII)
            }
            output.write(next)
            previous = next
        }
    }

    override fun close() {
        runCatching { server.close() }
        worker.join(1_000)
    }
}

class AssistantProviderJvmTest {
    @Test
    fun geminiCompatiblePathUsesExistingTransportWithoutChangingOpenAiPath() {
        assertTrue(AssistantEndpoint.isValid("https://generativelanguage.googleapis.com/v1beta/openai/chat/completions"))
        assertTrue(AssistantEndpoint.isValid("https://api.openai.com/v1/chat/completions"))
        val seenPath = AtomicReference<String>()
        val seenAuthorization = AtomicReference<String>()
        val seenJson = AtomicReference<String>()
        withServer({ request ->
            seenPath.set(request.path)
            seenAuthorization.set(request.headers["authorization"])
            seenJson.set(request.body)
            FakeHttpResponse(body = validChatResponse("synthetic compatibility response"))
        }) { base ->
            val response = testProvider().complete("$base/v1beta/openai/chat/completions", "synthetic-gemini-model", FAKE_KEY, "synthetic only", "system")
            assertEquals("synthetic compatibility response", response)
            assertEquals("/v1beta/openai/chat/completions", seenPath.get())
            assertEquals("Bearer $FAKE_KEY", seenAuthorization.get())
            assertEquals("synthetic-gemini-model", JSONObject(seenJson.get()).getString("model"))
        }
    }

    @Test
    fun sendsExactEndpointPayloadAndAllowsMissingModel() {
        val seenPath = AtomicReference<String>()
        val seenAuthorization = AtomicReference<String>()
        val seenJson = AtomicReference<String>()
        withServer({ request ->
            seenPath.set(request.path)
            seenAuthorization.set(request.headers["authorization"])
            seenJson.set(request.body)
            FakeHttpResponse(body = validChatResponse("ok"))
        }) { base ->
            val endpoint = "$base/custom/selected/path"
            val result = testProvider().complete(endpoint, "", FAKE_KEY, "payload exact", "system exact")
            assertEquals("ok", result)
            assertEquals("/custom/selected/path", seenPath.get())
            assertEquals("Bearer $FAKE_KEY", seenAuthorization.get())
            val body = JSONObject(seenJson.get())
            assertFalse(body.has("model"))
            val messages = body.getJSONArray("messages")
            assertEquals("system", messages.getJSONObject(0).getString("role"))
            assertEquals("system exact", messages.getJSONObject(0).getString("content"))
            assertEquals("payload exact", messages.getJSONObject(1).getString("content"))
        }
    }

    @Test
    fun unauthorizedAndRateLimitErrorsNeverExposeSecretsOrRawBody() {
        for (status in listOf(401, 403, 429)) {
            withServer({
                FakeHttpResponse(status = status, body = "provider echoed $FAKE_KEY RAW_PROVIDER_DETAIL")
            }) { base ->
                val error = assertThrows(AssistantException::class.java) {
                    testProvider().complete("$base/v1", "model", FAKE_KEY, "synthetic", "system")
                }
                assertFalse(error.message.orEmpty().contains(FAKE_KEY))
                assertFalse(error.message.orEmpty().contains("RAW_PROVIDER_DETAIL"))
                assertTrue(error.message.orEmpty().any { it.code > 127 })
            }
        }
    }

    @Test
    fun redirectsAreNotFollowedAndCredentialsAreNotForwarded() {
        val targetHits = AtomicInteger()
        withServer({
            FakeHttpResponse(status = 302, headers = mapOf("Location" to targetHitsUrl.get()), body = "redirect detail")
        }) { firstBase ->
            withServer({
                targetHits.incrementAndGet()
                FakeHttpResponse(body = validChatResponse("unexpected"))
            }) { secondBase ->
                targetHitsUrl.set("$secondBase/steal")
                val failure = assertThrows(AssistantException::class.java) {
                    testProvider().complete("$firstBase/exact", "model", FAKE_KEY, "synthetic", "system")
                }
                assertTrue(failure.message.orEmpty().contains("التحويل"))
                assertEquals(0, targetHits.get())
                assertFalse(failure.message.orEmpty().contains(FAKE_KEY))
            }
        }
    }

    @Test
    fun timeoutAndMalformedResponseHaveSanitizedMessages() {
        withServer({
            Thread.sleep(700)
            FakeHttpResponse(body = "provider secret $FAKE_KEY")
        }) { base ->
            val shortClient = OkHttpClient.Builder()
                .connectTimeout(300, TimeUnit.MILLISECONDS)
                .readTimeout(100, TimeUnit.MILLISECONDS)
                .callTimeout(250, TimeUnit.MILLISECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .build()
            val timeout = assertThrows(AssistantException::class.java) {
                OpenAiCompatibleProvider(shortClient, allowCleartextForTests = true)
                    .complete("$base/slow", "", FAKE_KEY, "synthetic", "system")
            }
            assertTrue(timeout.message.orEmpty().contains("مهلة"))
            assertFalse(timeout.message.orEmpty().contains(FAKE_KEY))
        }

        withServer({ FakeHttpResponse(body = "not-json raw-secret") }) { base ->
            val malformed = assertThrows(AssistantException::class.java) {
                testProvider().complete("$base/malformed", "", FAKE_KEY, "synthetic", "system")
            }
            assertTrue(malformed.message.orEmpty().contains("استجابة"))
            assertFalse(malformed.message.orEmpty().contains("raw-secret"))
        }
    }

    @Test
    fun connectionRefusedProducesFriendlySanitizedError() {
        val reservation = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val unusedPort = reservation.localPort
        reservation.close()
        val error = assertThrows(AssistantException::class.java) {
            testProvider().complete("http://127.0.0.1:$unusedPort/v1", "", FAKE_KEY, "synthetic", "system")
        }
        assertTrue(error.message.orEmpty().contains("الاتصال"))
        assertFalse(error.message.orEmpty().contains(FAKE_KEY))
    }

    @Test
    fun responseBodyIsBoundedAndProductionRejectsCleartext() {
        withServer({ FakeHttpResponse(body = "x".repeat(300 * 1024)) }) { base ->
            val error = assertThrows(AssistantException::class.java) {
                testProvider().complete("$base/large", "", FAKE_KEY, "synthetic", "system")
            }
            assertTrue(error.message.orEmpty().contains("الحد"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            AssistantEndpoint.requireValid("http://example.invalid/v1", allowCleartextForTests = false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AssistantEndpoint.requireValid("https://user:pass@example.invalid/v1")
        }
        assertThrows(IllegalArgumentException::class.java) {
            AssistantEndpoint.requireValid("https://example.invalid/v1?api_key=secret")
        }
    }

    @Test
    fun connectionCheckSendsOnlyFixedSyntheticText() {
        val requestBody = AtomicReference<String>()
        withServer({ request ->
            requestBody.set(request.body)
            FakeHttpResponse(body = validChatResponse("ready"))
        }) { base ->
            testProvider().testConnection("$base/test", "", FAKE_KEY)
            val content = JSONObject(requestBody.get()).getJSONArray("messages")
                .getJSONObject(1).getString("content")
            assertTrue(content.contains("كلمة واحدة"))
            assertFalse(content.contains("user input"))
        }
    }

    private fun testProvider() = OpenAiCompatibleProvider(
        OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build(),
        allowCleartextForTests = true,
    )

    companion object {
        private const val FAKE_KEY = "fake-only-test-key-not-a-real-secret"
        private val targetHitsUrl = AtomicReference<String>("")

        private fun validChatResponse(text: String) = JSONObject()
            .put("choices", JSONArray().put(JSONObject().put("message", JSONObject().put("content", text))))
            .toString()

        private fun withServer(handler: (FakeHttpRequest) -> FakeHttpResponse, block: (String) -> Unit) {
            val server = FakeHttpServer(handler)
            try {
                block(server.baseUrl)
            } finally {
                server.close()
            }
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35], application = android.app.Application::class)
class AssistantStoreAndParserTest {
    @Test
    fun storeStartsDisabledAndEndpointChangesDeleteTheOldKey() {
        val context = RuntimeEnvironment.getApplication() as Context
        val directory = java.io.File(context.noBackupFilesDir, "assistant")
        directory.deleteRecursively()
        val store = AssistantStore(context, TestCipher)
        assertFalse(store.load().enabled)
        assertFalse(store.load().hasKey)

        store.save("https://one.example/v1/chat/completions", "model-a", "fake-secret-one", enabled = true)
        assertTrue(store.load().enabled)
        assertEquals("fake-secret-one", store.readKey())
        assertEquals(AssistantProviderIds.OPENAI_COMPATIBLE, store.load().provider)
        assertFalse(String(java.io.File(directory, "api-key.enc").readBytes(), StandardCharsets.UTF_8)
            .contains("fake-secret-one"))

        store.save("https://two.example/custom/path", "model-b", "fake-secret-two", enabled = true)
        assertEquals("https://two.example/custom/path", store.load().endpoint)
        assertTrue(store.load().enabled)
        assertTrue(store.load().hasKey)
        assertEquals("fake-secret-two", store.readKey())
        val restartedStore = AssistantStore(context, TestCipher)
        assertEquals("fake-secret-two", restartedStore.readKey())
        restartedStore.save("https://two.example/custom/path", "model-b", "fake-replacement-key", enabled = true)
        assertEquals("fake-replacement-key", restartedStore.readKey())
        store.deleteKey()
        assertFalse(store.load().enabled)
        assertFalse(store.load().hasKey)
        assertNull(store.readKey())
    }

    @Test
    fun legacyConfigWithoutProviderLoadsAsOpenAiCompatible() {
        val context = RuntimeEnvironment.getApplication() as Context
        val directory = java.io.File(context.noBackupFilesDir, "assistant")
        directory.deleteRecursively()
        directory.mkdirs()
        java.io.File(directory, "config.json").writeText(
            """{"endpoint":"https://legacy.example/v1/chat/completions","model":"legacy-model","enabled":false}""",
        )

        val config = AssistantStore(context, TestCipher).load()
        assertEquals(AssistantProviderIds.OPENAI_COMPATIBLE, config.provider)
        assertEquals("https://legacy.example/v1/chat/completions", config.endpoint)
        assertFalse(config.enabled)
    }

    @Test
    fun selectedProviderIsPersistedAndNewCredentialIsSavedInSameProviderChange() {
        val context = RuntimeEnvironment.getApplication() as Context
        java.io.File(context.noBackupFilesDir, "assistant").deleteRecursively()
        val store = AssistantStore(context, TestCipher)
        store.save("https://openai.example/v1", "model-a", "fake-openai-key", enabled = true)

        store.save(
            "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
            "gemini-3.8-flash",
            "fake-gemini-key",
            enabled = true,
            provider = AssistantProviderIds.GEMINI,
        )

        assertEquals(AssistantProviderIds.GEMINI, store.load().provider)
        assertEquals("fake-gemini-key", store.readKey())
        assertTrue(store.load().enabled)
    }

    @Test
    fun endpointChangeWithoutNewKeyDisablesAndDeletesPreviousCredential() {
        val context = RuntimeEnvironment.getApplication() as Context
        java.io.File(context.noBackupFilesDir, "assistant").deleteRecursively()
        val store = AssistantStore(context, TestCipher)
        store.save("https://one.example/v1", "model-a", "fake-old-key", enabled = true)

        store.save("https://two.example/v1", "model-b", null, enabled = true)

        assertEquals("https://two.example/v1", store.load().endpoint)
        assertFalse(store.load().enabled)
        assertFalse(store.load().hasKey)
        assertNull(store.readKey())
    }

    @Test
    fun rejectedProviderEndpointModelOrKeyDoesNotDamagePreviousConfiguration() {
        val context = RuntimeEnvironment.getApplication() as Context
        java.io.File(context.noBackupFilesDir, "assistant").deleteRecursively()
        val store = AssistantStore(context, TestCipher)
        store.save("https://valid.example/v1", "valid-model", "fake-preserved-key", enabled = true)
        val original = store.load()

        assertThrows(IllegalArgumentException::class.java) {
            store.save("https://invalid.example/v1", "next-model", "new-key", true, "unsupported")
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.save("http://invalid.example/v1", "next-model", "new-key", true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.save("https://valid.example/v1", "m".repeat(201), null, true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.save("https://valid.example/v1", "next-model", "bad\nkey", true)
        }

        assertEquals(original, store.load())
        assertEquals("fake-preserved-key", store.readKey())
    }

    @Test
    fun parserValidatesDatesPrioritiesAndKnownPlaceIds() {
        val parsed = AssistantSuggestionParser.parse(
            """{"suggestions":[{"title":"شراء دواء","description":"","priority":2,"dueDate":"2024-02-29","placeId":"home"}],"advice":"اقتراح فقط"}""",
            setOf("home"),
        )
        assertEquals(1, parsed.suggestions.size)
        assertEquals(2, parsed.suggestions.single().priority)
        assertEquals("", parsed.suggestions.single().description)
        assertEquals("2024-02-29", parsed.suggestions.single().dueDate)
        assertEquals("home", parsed.suggestions.single().placeId)
        val noDate = AssistantSuggestionParser.parse(
            """{"suggestions":[{"title":"مهمة","description":"وصف","priority":0}],"advice":""}""",
            emptySet(),
        )
        assertEquals("", noDate.suggestions.single().dueDate)
    }

    @Test
    fun parserRejectsUnknownPlacesInvalidDatesPrioritiesAndCoordinates() {
        val invalidResponses = listOf(
            suggestionJson(priority = "3"),
            suggestionJson(priority = "-1"),
            suggestionJson(priority = "1.5"),
            suggestionJson(date = "2025-02-29"),
            suggestionJson(date = "2024-2-29"),
            suggestionJson(placeId = "somewhere-unknown"),
            suggestionJson(extra = "\"latitude\": 12.3"),
        )
        for (json in invalidResponses) {
            assertThrows(IllegalArgumentException::class.java) {
                AssistantSuggestionParser.parse(json, setOf("known"))
            }
        }
    }

    private fun suggestionJson(
        priority: String = "1",
        date: String = "2024-05-15",
        placeId: String = "known",
        extra: String = "",
    ) = """{"suggestions":[{"title":"مهمة","description":"وصف","priority":$priority,"dueDate":"$date","placeId":"$placeId"${if (extra.isNotEmpty()) ",$extra" else ""}}],"advice":""}"""

    private object TestCipher : AssistantKeyCipher {
        override fun encrypt(plaintext: ByteArray): ByteArray = plaintext.map { (it.toInt() xor 0x5a).toByte() }.toByteArray()
        override fun decrypt(ciphertext: ByteArray): ByteArray = encrypt(ciphertext)
    }
}
