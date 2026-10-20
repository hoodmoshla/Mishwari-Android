package ye.mishwari.ai

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

interface AssistantProvider {
    fun complete(
        endpoint: String,
        model: String,
        key: String,
        prompt: String,
        systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    ): String

    companion object {
        const val DEFAULT_SYSTEM_PROMPT = "You are a helpful assistant. Return a concise answer."
    }
}

/** Safe-to-display provider failure; intentionally contains no server body, URL, or credential. */
class AssistantException(message: String) : Exception(message)

class OpenAiCompatibleProvider internal constructor(
    private val client: OkHttpClient,
    private val allowCleartextForTests: Boolean = false,
) : AssistantProvider {
    constructor() : this(
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build(),
    )

    override fun complete(endpoint: String, model: String, key: String, prompt: String, systemPrompt: String): String {
        val url = AssistantEndpoint.requireValid(endpoint, allowCleartextForTests)
        require(key.isNotBlank() && key.none { it == '\r' || it == '\n' || it == '\u0000' }) {
            "مفتاح API غير صالح"
        }
        require(key.length <= MAX_KEY_LENGTH) { "مفتاح API غير صالح" }
        require(prompt.isNotBlank() && prompt.length <= MAX_PROMPT_CHARS) { "نص الطلب فارغ أو أطول من المسموح" }
        require(model.length <= MAX_MODEL_LENGTH) { "اسم النموذج أطول من المسموح" }
        require(systemPrompt.isNotBlank() && systemPrompt.length <= MAX_SYSTEM_PROMPT_CHARS) { "تعليمات المساعد فارغة أو أطول من المسموح" }

        val messages = org.json.JSONArray()
            .put(JSONObject().put("role", "system").put("content", systemPrompt))
            .put(JSONObject().put("role", "user").put("content", prompt))
        val payload = JSONObject().put("messages", messages)
        if (model.isNotBlank()) payload.put("model", model)
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $key")
            .header("Accept", "application/json")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.byteStream()?.let { readBounded(it, MAX_RESPONSE_BYTES) }
                if (body == null) throw AssistantException("استجابة الخدمة فارغة")
                if (!response.isSuccessful) {
                    when (response.code) {
                        in 300..399 -> throw AssistantException("رفضت الخدمة التحويل إلى عنوان آخر")
                        401, 403 -> throw AssistantException("رفضت الخدمة بيانات المصادقة")
                        429 -> throw AssistantException("الخدمة مشغولة؛ حاول لاحقًا")
                        else -> throw AssistantException("تعذر إكمال الطلب لدى الخدمة (HTTP ${response.code})")
                    }
                }
                return extractAssistantText(body)
            }
        } catch (e: AssistantException) {
            throw e
        } catch (_: SocketTimeoutException) {
            throw AssistantException("انتهت مهلة الاتصال بالمساعد")
        } catch (_: IOException) {
            throw AssistantException("تعذر الاتصال بالمساعد؛ تحقق من الشبكة والعنوان")
        } catch (_: Exception) {
            throw AssistantException("تعذر قراءة استجابة المساعد")
        }
    }

    /** Uses a fixed synthetic prompt only; it never forwards user data. */
    fun testConnection(endpoint: String, model: String, key: String) {
        complete(endpoint, model, key, SYNTHETIC_TEST_PROMPT, AssistantProvider.DEFAULT_SYSTEM_PROMPT)
    }

    private fun readBounded(input: java.io.InputStream, maximum: Int): ByteArray {
        input.use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                total += count
                if (total > maximum) throw AssistantException("تجاوزت استجابة الخدمة الحد المسموح")
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        }
    }

    private fun extractAssistantText(bytes: ByteArray): String {
        try {
            val root = JSONObject(String(bytes, Charsets.UTF_8))
            val choices = root.optJSONArray("choices") ?: throw JSONException("missing choices")
            val content = choices.optJSONObject(0)
                ?.optJSONObject("message")
                ?.opt("content") as? String
            if (content.isNullOrBlank()) throw JSONException("missing content")
            return content
        } catch (_: Exception) {
            throw AssistantException("تعذر فهم استجابة المساعد؛ أعد المحاولة")
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_SECONDS = 10L
        const val READ_TIMEOUT_SECONDS = 20L
        const val WRITE_TIMEOUT_SECONDS = 20L
        const val CALL_TIMEOUT_SECONDS = 30L
        const val MAX_RESPONSE_BYTES = 256 * 1024
        const val MAX_PROMPT_CHARS = 32_000
        const val MAX_SYSTEM_PROMPT_CHARS = 8_000
        const val MAX_KEY_LENGTH = 4096
        const val MAX_MODEL_LENGTH = 200
        const val SYNTHETIC_TEST_PROMPT = "أجب بكلمة واحدة فقط: جاهز"
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

internal object AssistantEndpoint {
    fun isValid(endpoint: String): Boolean = isValid(endpoint, allowCleartextForTests = false)

    private fun isValid(endpoint: String, allowCleartextForTests: Boolean): Boolean = try {
        val parsed = endpoint.toHttpUrlOrNull() ?: return false
        (parsed.isHttps || (allowCleartextForTests && parsed.scheme == "http")) && parsed.host.isNotBlank() &&
            parsed.username.isEmpty() && parsed.password.isEmpty() &&
            parsed.encodedFragment == null && parsed.encodedQuery == null
    } catch (_: Exception) {
        false
    }

    fun requireValid(endpoint: String, allowCleartextForTests: Boolean = false): HttpUrl {
        if (!isValid(endpoint, allowCleartextForTests)) throw IllegalArgumentException("أدخل عنوان HTTPS كاملًا صالحًا دون بيانات اعتماد أو query")
        return endpoint.toHttpUrlOrNull() ?: throw IllegalArgumentException("أدخل عنوان HTTPS كاملًا صالحًا دون بيانات اعتماد أو query")
    }
}
