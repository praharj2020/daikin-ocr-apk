package pt.casa.daikinocr

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** Lê o número do visor com o Google Gemini (API gratuita do AI Studio). */
object GeminiReader {
    private const val TAG = "GeminiReader"

    data class Answer(val value: Int?, val raw: String, val error: String? = null)

    private const val PROMPT =
        "This image shows the 7-segment LED display of a water heater, showing the water temperature in degrees Celsius. " +
            "Reply with ONLY the number shown on the display, digits only (no unit, no words). " +
            "If the digits are not clearly readable, reply exactly NONE."

    fun read(crop: Bitmap, apiKey: String, model: String): Answer {
        if (apiKey.isBlank()) return Answer(null, "", "sem chave")
        val jpeg = ByteArrayOutputStream().use { crop.compress(Bitmap.CompressFormat.JPEG, 90, it); it.toByteArray() }
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray()
                .put(JSONObject().put("inline_data", JSONObject()
                    .put("mime_type", "image/jpeg")
                    .put("data", Base64.encodeToString(jpeg, Base64.NO_WRAP))))
                .put(JSONObject().put("text", PROMPT)))))
            .put("generationConfig", JSONObject().put("temperature", 0).put("maxOutputTokens", 512))
        return try {
            val url = URL("https://generativelanguage.googleapis.com/v1beta/models/${model.trim()}:generateContent")
            val c = url.openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.connectTimeout = 20000
            c.readTimeout = 40000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("x-goog-api-key", apiKey.trim())
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
            c.disconnect()
            if (code !in 200..299) {
                val msg = try { JSONObject(text).getJSONObject("error").getString("message") } catch (_: Exception) { text.take(120) }
                return Answer(null, "", "HTTP $code: $msg")
            }
            val out = JSONObject(text).getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts")
            val sb = StringBuilder()
            for (i in 0 until out.length()) sb.append(out.getJSONObject(i).optString("text"))
            val raw = sb.toString().trim()
            val num = Regex("^\\s*(\\d{1,3})\\s*$").find(raw)?.groupValues?.get(1)?.toIntOrNull()
            Answer(num, raw)
        } catch (e: Exception) {
            Log.w(TAG, "Gemini falhou", e)
            Answer(null, "", e.javaClass.simpleName + ": " + (e.message ?: ""))
        }
    }
}
