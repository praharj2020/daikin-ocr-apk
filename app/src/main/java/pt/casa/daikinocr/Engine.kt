package pt.casa.daikinocr

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
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

    data class Reading(
        val value: Int?, val flash: Boolean, val source: String, val detail: String, val frame: GrayFrame?
    )

    fun roiFor(frame: GrayFrame): SevenSegOcr.Roi? {
        val r = prefs.roi ?: return null
        return SevenSegOcr.Roi(
            (r[0] * frame.width).toInt(), (r[1] * frame.height).toInt(),
            (r[2] * frame.width).toInt(), (r[3] * frame.height).toInt()
        )
    }

    /** Leitura local (7 segmentos) com votação entre fotogramas. */
    fun readLocal(frames: List<GrayFrame>): Pair<Int?, Map<Int, Int>> {
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
        return (if (best.value * 2 > reads) best.key else null) to total
    }

    /** Contraste dos números na ROI (percentil 98 - mediana). Baixo => pouca luz / desfocado. */
    fun roiContrast(f: GrayFrame): Int {
        val roi = roiFor(f) ?: return 255
        val hist = IntArray(256)
        var n = 0
        for (y in roi.y0 until roi.y1.coerceAtMost(f.height)) for (x in roi.x0 until roi.x1.coerceAtMost(f.width)) {
            hist[f.data[y * f.width + x].toInt() and 0xFF]++; n++
        }
        if (n == 0) return 0
        fun pct(p: Double): Int { var acc = 0; val lim = n * p; for (i in 0 until 256) { acc += hist[i]; if (acc >= lim) return i }; return 255 }
        return pct(0.98) - pct(0.5)
    }

    /** Recorte da ROI com margem, ampliado, para enviar ao Gemini / guardar na Galeria. */
    fun cropBitmap(f: GrayFrame, margin: Float = 0.35f): Bitmap? {
        val roi = roiFor(f) ?: return null
        val mw = ((roi.x1 - roi.x0) * margin).toInt()
        val mh = ((roi.y1 - roi.y0) * margin).toInt()
        val x0 = (roi.x0 - mw).coerceAtLeast(0)
        val y0 = (roi.y0 - mh).coerceAtLeast(0)
        val x1 = (roi.x1 + mw).coerceAtMost(f.width)
        val y1 = (roi.y1 + mh).coerceAtMost(f.height)
        val w = x1 - x0
        val h = y1 - y0
        if (w <= 0 || h <= 0) return null
        val px = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val g = f.data[(y0 + y) * f.width + x0 + x].toInt() and 0xFF
            px[y * w + x] = (0xFF shl 24) or (g shl 16) or (g shl 8) or g
        }
        val bmp = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
        val scale = if (w < 640) 640f / w else 1f
        return if (scale > 1f) Bitmap.createScaledBitmap(bmp, (w * scale).toInt(), (h * scale).toInt(), true) else bmp
    }

    private fun shoot(flash: Boolean): Reading {
        val frames = grabber.grab(flash, count = 3, zoom = prefs.zoom, roi = prefs.roi)
        val frame = frames.lastOrNull() ?: return Reading(null, flash, "-", "sem imagem", null)
        val (local, votes) = readLocal(frames)
        val localOk = local?.takeIf { it in MIN_OK..MAX_OK }
        var detail = "local=${local ?: "?"} $votes"
        if (prefs.geminiKey.isNotBlank()) {
            val crop = cropBitmap(frame)
            if (crop != null) {
                val g = GeminiReader.read(crop, prefs.geminiKey, prefs.geminiModel)
                detail = "gemini=${g.value ?: g.error ?: g.raw.take(20)} · $detail"
                val gv = g.value?.takeIf { it in MIN_OK..MAX_OK }
                if (gv != null) return Reading(gv, flash, if (gv == localOk) "gemini+local" else "gemini", detail, frame)
            }
        }
        return Reading(localOk, flash, "local", detail, frame)
    }

    /** Luz automática: decide o flash pelo contraste dos números; repete com o outro modo se falhar. */
    fun capture(): Reading {
        var useFlash = prefs.lastNeededFlash
        var r = shoot(useFlash)
        if (r.value == null) {
            val other = shoot(!useFlash)
            if (other.value != null) { r = other; useFlash = !useFlash }
        } else if (!useFlash) {
            // leu sem flash; se o contraste estiver fraco, da próxima vez começa com flash
            r.frame?.let { if (roiContrast(it) < 30) useFlash = true }
        }
        if (r.value != null) prefs.lastNeededFlash = useFlash && r.flash
        val last = prefs.lastValue
        if (r.value != null && last >= 0 && kotlin.math.abs(r.value!! - last) > MAX_JUMP) {
            val c = shoot(r.flash)
            if (c.value != r.value) return r.copy(value = null, detail = "salto ${last}→${r.value} não confirmado · ${r.detail}")
        }
        return r
    }

    fun runCycle(): String = synchronized(lock) {
        val time = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date())
        val status: String = try {
            val r = capture()
            r.frame?.let { saveFrame(it); saveToGallery(it, r.value) }
            if (r.value == null) {
                "$time  sem leitura (${r.detail})"
            } else {
                prefs.lastValue = r.value
                val sent = send(r.value, r.flash, r.source)
                "$time  ${r.value}°C [${r.source}]${if (r.flash) " flash" else ""} → ${if (sent) "enviado" else "ERRO envio"}"
            }
        } catch (e: Exception) {
            Log.e(TAG, "ciclo falhou", e)
            "$time  erro: ${e.message}"
        }
        prefs.lastStatus = status
        prefs.addHistory(status)
        status
    }

    fun send(value: Int, flash: Boolean, source: String): Boolean {
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
                val body = """{"temperatura": $value, "flash": $flash, "fonte": "$source", "origem": "daikin-ocr-android"}"""
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

    /** Guarda o recorte dos números em Imagens/DaikinOCR (e a foto inteira quando não leu). */
    fun saveToGallery(f: GrayFrame, value: Int?) {
        if (Build.VERSION.SDK_INT < 29) return
        try {
            val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
            val items = mutableListOf<Pair<String, Bitmap>>()
            cropBitmap(f)?.let { items += "${stamp}_${value ?: "X"}_recorte.jpg" to it }
            if (value == null) items += "${stamp}_X_inteira.jpg" to toBitmap(f)
            for ((name, bmp) in items) {
                val cv = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/DaikinOCR")
                }
                val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv) ?: continue
                ctx.contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "galeria falhou", e)
        }
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
