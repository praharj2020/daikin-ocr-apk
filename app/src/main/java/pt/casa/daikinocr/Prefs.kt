package pt.casa.daikinocr

import android.content.Context
import android.content.SharedPreferences

class Prefs(ctx: Context) {
    private val p: SharedPreferences = ctx.getSharedPreferences("daikin_ocr", Context.MODE_PRIVATE)

    var webhookUrl: String
        get() = p.getString("webhook", null)?.takeIf { it.isNotBlank() } ?: BuildConfig.WEBHOOK_URL
        set(v) = p.edit().putString("webhook", v.trim()).apply()

    var intervalMin: Int
        get() = p.getInt("interval", 10)
        set(v) = p.edit().putInt("interval", v.coerceIn(1, 240)).apply()

    var zoom: Float
        get() = p.getFloat("zoom", 1f)
        set(v) = p.edit().putFloat("zoom", v.coerceIn(1f, 4f)).apply()

    /** ROI normalizada (0..1) em relação ao fotograma em pé. */
    var roi: FloatArray?
        get() {
            val s = p.getString("roi", null) ?: return null
            val parts = s.split(",").mapNotNull { it.toFloatOrNull() }
            return if (parts.size == 4) parts.toFloatArray() else null
        }
        set(v) = p.edit().putString("roi", v?.joinToString(",")).apply()

    var geminiKey: String
        get() = p.getString("gemini_key", "") ?: ""
        set(v) = p.edit().putString("gemini_key", v.trim()).apply()

    var geminiModel: String
        get() = p.getString("gemini_model", null)?.takeIf { it.isNotBlank() } ?: "gemini-flash-lite-latest"
        set(v) = p.edit().putString("gemini_model", v.trim()).apply()

    var lastNeededFlash: Boolean
        get() = p.getBoolean("last_flash", false)
        set(v) = p.edit().putBoolean("last_flash", v).apply()

    var lastUpdateCheck: Long
        get() = p.getLong("last_update_check", 0L)
        set(v) = p.edit().putLong("last_update_check", v).apply()

    var running: Boolean
        get() = p.getBoolean("running", false)
        set(v) = p.edit().putBoolean("running", v).apply()

    var lastValue: Int
        get() = p.getInt("last_value", -1)
        set(v) = p.edit().putInt("last_value", v).apply()

    var lastStatus: String
        get() = p.getString("last_status", "—") ?: "—"
        set(v) = p.edit().putString("last_status", v).apply()

    var history: String
        get() = p.getString("history", "") ?: ""
        set(v) = p.edit().putString("history", v).apply()

    fun addHistory(line: String) {
        val lines = (listOf(line) + history.split("\n").filter { it.isNotBlank() }).take(30)
        history = lines.joinToString("\n")
    }
}
