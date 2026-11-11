package pt.casa.daikinocr

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Um ciclo completo: fotografar -> ler -> validar -> enviar ao Home Assistant. */
class Engine(private val ctx: Context) {

    companion object {
        private const val TAG = "DaikinEngine"
        private const val MIN_OK = 5
        private const val MAX_OK = 85
        private const val MAX_JUMP = 20
        val lock = Any()
    }

    private val prefs = Prefs(ctx)
    private val grabber = CameraGrabber(ctx)

    data class Reading(val value: Int?, val flash: Boolean, val votes: Map<Int, Int>, val frame: GrayFrame?)

    fun roiFor(frame: GrayFrame): SevenSegOcr.Roi? {
        val r = prefs.roi ?: return null
        return SevenSegOcr.Roi(
            (r[0] * frame.width).toInt(), (r[1] * frame.height).toInt(),
            (r[2] * frame.width).toInt(), (r[3] * frame.height).toInt()
        )
    }

    /** Lê vários fotogramas e soma os votos. */
    fun readFrames(frames: List<GrayFrame>): Pair<Int?, Map<Int, Int>> {
        val total = HashMap<Int, Int>()
        var reads = 0
        for (f in frames) {
            val roi = roiFor(f) ?: return null to emptyMap()
            val r = SevenSegOcr.read(f.data, f.width, f.height, roi)
            if (r.value != null) {
                total[r.value] = (total[r.value] ?: 0) + 1
                reads++
            }
        }
        if (total.isEmpty()) return null to total
        val best = total.maxByOrNull { it.value }!!
        // maioria absoluta entre os fotogramas lidos
        return (if (best.value * 2 > reads) best.key else null) to total
    }

    private fun shoot(flash: Boolean): Reading {
        val frames = grabber.grab(flash, count = 3, zoom = prefs.zoom)
        val (v, votes) = readFrames(frames)
        return Reading(v?.takeIf { it in MIN_OK..MAX_OK }, flash, votes, frames.lastOrNull())
    }

    /** Fotografa com flash automático: sem flash primeiro; com flash se não conseguir ler. */
    fun capture(): Reading {
        var r = shoot(false)
        if (r.value == null) r = shoot(true)
        // salto grande face à última leitura: confirma com nova foto
        val last = prefs.lastValue
        if (r.value != null && last >= 0 && kotlin.math.abs(r.value!! - last) > MAX_JUMP) {
            val c = shoot(r.flash)
            if (c.value != r.value) return Reading(null, r.flash, r.votes, r.frame)
        }
        return r
    }

    fun runCycle(): String = synchronized(lock) {
        val time = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date())
        val status: String = try {
            val r = capture()
            r.frame?.let { saveFrame(it) }
            if (r.value == null) {
                "$time  sem leitura (votos ${r.votes})"
            } else {
                prefs.lastValue = r.value
                val sent = send(r.value, r.flash)
                "$time  ${r.value}°C${if (r.flash) " (flash)" else ""}  →  ${if (sent) "enviado" else "ERRO envio"}"
            }
        } catch (e: Exception) {
            Log.e(TAG, "ciclo falhou", e)
            "$time  erro: ${e.message}"
        }
        prefs.lastStatus = status
        prefs.addHistory(status)
        status
    }

    fun send(value: Int, flash: Boolean): Boolean {
        val url = prefs.webhookUrl
        if (url.isBlank()) return false
        repeat(3) { attempt ->
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.requestMethod = "POST"
                c.connectTimeout = 15000
                c.readTimeout = 15000
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                val body = """{"temperatura": $value, "flash": $flash, "origem": "daikin-ocr-android"}"""
                c.outputStream.use { it.write(body.toByteArray()) }
                val code = c.responseCode
                c.disconnect()
                if (code in 200..299) return true
            } catch (e: Exception) {
                Log.w(TAG, "envio falhou (tentativa ${attempt + 1})", e)
            }
            Thread.sleep(5000)
        }
        return false
    }

    fun frameFile() = File(ctx.filesDir, "ultima.jpg")

    fun saveFrame(f: GrayFrame) {
        val bmp = toBitmap(f)
        FileOutputStream(frameFile()).use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
    }

    fun toBitmap(f: GrayFrame): Bitmap {
        val px = IntArray(f.width * f.height)
        for (i in px.indices) {
            val g = f.data[i].toInt() and 0xFF
            px[i] = (0xFF shl 24) or (g shl 16) or (g shl 8) or g
        }
        return Bitmap.createBitmap(px, f.width, f.height, Bitmap.Config.ARGB_8888)
    }
}
