package ai.drivemuse.app.gemini

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.net.URLEncoder
import javax.net.ssl.HttpsURLConnection

/** The direct path failed in a way the settings screen has to explain differently. */
class GeminiDirectException(val kind: Kind, message: String) : IllegalStateException(message) {
    enum class Kind { AUTH, MODEL, QUOTA, NETWORK, EMPTY }
}

/**
 * Technical design v2.3 §22: "개인 Gemini 직접 연결". An explicitly chosen personal mode that calls
 * the Gemini API with the user's own key instead of going through Firebase AI Logic.
 *
 * The key lives in the encrypted credential store and is sent only to generativelanguage.googleapis.com.
 * There is no App Check here — a key on a device cannot be kept secret from the device's owner — so
 * this stays a personal-use mode, and a distributed build should use the managed Firebase path.
 */
class GeminiDirectClient(private val apiKey: String, private val modelId: String) {

    suspend fun generateJson(system: String, userText: String, maxOutputTokens: Int = 1500): String = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", userText)))))
            .put("generationConfig", JSONObject()
                .put("responseMimeType", "application/json")
                .put("maxOutputTokens", maxOutputTokens)
                .put("temperature", 0.2))
            .toString()

        val url = "https://generativelanguage.googleapis.com/v1beta/models/" +
            URLEncoder.encode(modelId, "UTF-8") + ":generateContent?key=" + URLEncoder.encode(apiKey, "UTF-8")
        val connection = try {
            (URL(url).openConnection() as HttpsURLConnection).apply {
                requestMethod = "POST"; doOutput = true; instanceFollowRedirects = false
                connectTimeout = 6000; readTimeout = 20000
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
            }
        } catch (e: Exception) { throw GeminiDirectException(GeminiDirectException.Kind.NETWORK, e.message ?: "연결 실패") }

        try {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            if (code !in 200..299) {
                val detail = runCatching {
                    val raw = connection.errorStream?.use { String(it.readNBytes(200_000), Charsets.UTF_8) }.orEmpty()
                    JSONObject(raw).optJSONObject("error")?.optString("message").orEmpty()
                }.getOrDefault("")
                throw GeminiDirectException(when (code) {
                    400, 401, 403 -> GeminiDirectException.Kind.AUTH
                    404 -> GeminiDirectException.Kind.MODEL
                    429 -> GeminiDirectException.Kind.QUOTA
                    else -> GeminiDirectException.Kind.NETWORK
                }, detail.ifBlank { "응답 코드 $code" })
            }
            val bytes = connection.inputStream.use { it.readNBytes(2_000_001) }
            require(bytes.size <= 2_000_000) { "응답 크기 초과" }
            val root = JSONObject(String(bytes, Charsets.UTF_8))
            val parts = root.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
            val text = (0 until (parts?.length() ?: 0)).mapNotNull { parts?.optJSONObject(it)?.optString("text")?.takeIf { t -> t.isNotBlank() } }
                .joinToString("")
            if (text.isBlank()) throw GeminiDirectException(GeminiDirectException.Kind.EMPTY, "빈 응답")
            text
        } finally { connection.disconnect() }
    }
}
