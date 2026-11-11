package pt.casa.daikinocr

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.util.Size
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Fotograma em tons de cinza, já rodado para a orientação "em pé". */
class GrayFrame(val data: ByteArray, val width: Int, val height: Int)

/**
 * Captura fotogramas da câmara traseira sem pré-visualização (funciona com o ecrã desligado
 * dentro de um serviço em primeiro plano do tipo "camera").
 * Foco e exposição são medidos na área dos números (ROI), quando conhecida.
 */
class CameraGrabber(private val ctx: Context) {

    companion object {
        private const val TAG = "CameraGrabber"
    }

    private fun backCameraId(cm: CameraManager): String {
        for (id in cm.cameraIdList) {
            val ch = cm.getCameraCharacteristics(id)
            if (ch.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK) return id
        }
        return cm.cameraIdList.first()
    }

    /** Converte a ROI normalizada (imagem em pé) para coordenadas do sensor. */
    private fun roiToSensor(roi: FloatArray, rotation: Int, crop: Rect): Rect {
        fun map(u: Float, v: Float): Pair<Float, Float> = when ((rotation % 360 + 360) % 360) {
            90 -> v to (1f - u)
            180 -> (1f - u) to (1f - v)
            270 -> (1f - v) to u
            else -> u to v
        }
        val (ax, ay) = map(roi[0], roi[1])
        val (bx, by) = map(roi[2], roi[3])
        // margem de 30% à volta dos números para a medição
        var x0 = minOf(ax, bx); var x1 = maxOf(ax, bx)
        var y0 = minOf(ay, by); var y1 = maxOf(ay, by)
        val mx = (x1 - x0) * 0.3f; val my = (y1 - y0) * 0.3f
        x0 = (x0 - mx).coerceIn(0f, 1f); x1 = (x1 + mx).coerceIn(0f, 1f)
        y0 = (y0 - my).coerceIn(0f, 1f); y1 = (y1 + my).coerceIn(0f, 1f)
        return Rect(
            crop.left + (x0 * crop.width()).toInt(), crop.top + (y0 * crop.height()).toInt(),
            crop.left + (x1 * crop.width()).toInt(), crop.top + (y1 * crop.height()).toInt()
        )
    }

    /**
     * Abre a câmara, foca e mede a luz na ROI, e devolve [count] fotogramas.
     * Bloqueia a thread que chama (não chamar na thread principal).
     */
    @SuppressLint("MissingPermission")
    fun grab(flash: Boolean, count: Int = 3, zoom: Float = 1f, roi: FloatArray? = null): List<GrayFrame> {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = backCameraId(cm)
        val ch = cm.getCameraCharacteristics(id)
        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        val sizes = map.getOutputSizes(ImageFormat.YUV_420_888)
        val size: Size = sizes.filter { it.width <= 2400 && it.height <= 1800 }
            .maxByOrNull { it.width.toLong() * it.height } ?: sizes.minByOrNull { it.width.toLong() * it.height }!!
        val rotation = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val hasFlash = ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        val active = ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: Rect(0, 0, size.width, size.height)
        val maxAf = ch.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0
        val maxAe = ch.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0
        val afModes = ch.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.toList() ?: emptyList()
        val canAutoFocus = afModes.contains(CaptureRequest.CONTROL_AF_MODE_AUTO)

        val crop = if (zoom > 1.01f) {
            val cw = (active.width() / zoom).toInt(); val chh = (active.height() / zoom).toInt()
            Rect(active.centerX() - cw / 2, active.centerY() - chh / 2, active.centerX() + cw / 2, active.centerY() + chh / 2)
        } else Rect(active)
        val meter = roi?.let { MeteringRectangle(roiToSensor(it, rotation, crop), MeteringRectangle.METERING_WEIGHT_MAX) }

        val thread = HandlerThread("cam").apply { start() }
        val handler = Handler(thread.looper)
        val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 4)

        val frames = ArrayList<GrayFrame>()
        val done = CountDownLatch(1)
        var device: CameraDevice? = null
        var session: CameraCaptureSession? = null
        val start = SystemClock.elapsedRealtime()
        val ready = java.util.concurrent.atomic.AtomicBoolean(false) // foco e exposição estabilizados
        val readySince = java.util.concurrent.atomic.AtomicLong(0L)
        var lastTaken = 0L

        reader.setOnImageAvailableListener({ r ->
            val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val now = SystemClock.elapsedRealtime()
                if (ready.get() && frames.size < count && now - readySince.get() > 300 && now - lastTaken > 250) {
                    val plane = img.planes[0]
                    val buf = plane.buffer
                    val rs = plane.rowStride
                    val ps = plane.pixelStride
                    val w = img.width
                    val h = img.height
                    val y = ByteArray(w * h)
                    if (ps == 1 && rs == w) {
                        buf.get(y, 0, w * h)
                    } else {
                        val row = ByteArray(rs)
                        for (j in 0 until h) {
                            buf.position(j * rs)
                            val len = minOf(rs, buf.remaining())
                            buf.get(row, 0, len)
                            for (i in 0 until w) y[j * w + i] = row[i * ps]
                        }
                    }
                    frames.add(rotate(y, w, h, rotation))
                    lastTaken = now
                    if (frames.size >= count) done.countDown()
                }
            } finally {
                img.close()
            }
        }, handler)

        try {
            val opened = CountDownLatch(1)
            cm.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(cam: CameraDevice) { device = cam; opened.countDown() }
                override fun onDisconnected(cam: CameraDevice) { cam.close(); opened.countDown(); done.countDown() }
                override fun onError(cam: CameraDevice, error: Int) {
                    Log.e(TAG, "erro câmara $error"); cam.close(); opened.countDown(); done.countDown()
                }
            }, handler)
            if (!opened.await(8, TimeUnit.SECONDS) || device == null) throw IllegalStateException("câmara não abriu")
            val cam = device!!

            val configured = CountDownLatch(1)
            @Suppress("DEPRECATION")
            cam.createCaptureSession(listOf(reader.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) { session = s; configured.countDown() }
                override fun onConfigureFailed(s: CameraCaptureSession) { configured.countDown() }
            }, handler)
            if (!configured.await(8, TimeUnit.SECONDS) || session == null) throw IllegalStateException("sessão falhou")
            val s = session!!

            fun builder() = cam.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(reader.surface)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.FLASH_MODE, if (flash && hasFlash) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
                set(CaptureRequest.CONTROL_AF_MODE, if (canAutoFocus) CaptureRequest.CONTROL_AF_MODE_AUTO else CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                if (meter != null) {
                    if (maxAf > 0) set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(meter))
                    if (maxAe > 0) set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(meter))
                }
                if (zoom > 1.01f) set(CaptureRequest.SCALER_CROP_REGION, crop)
            }

            var afState: Int? = null
            var aeState: Int? = null
            val cb = object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(sess: CameraCaptureSession, req: CaptureRequest, res: TotalCaptureResult) {
                    afState = res.get(CaptureResult.CONTROL_AF_STATE)
                    aeState = res.get(CaptureResult.CONTROL_AE_STATE)
                }
            }
            s.setRepeatingRequest(builder().build(), cb, handler)

            // 1) deixa a exposição convergir
            val aeDeadline = SystemClock.elapsedRealtime() + (if (flash) 2500 else 2000)
            while (SystemClock.elapsedRealtime() < aeDeadline) {
                Thread.sleep(100)
                val ae = aeState
                if (SystemClock.elapsedRealtime() - start > 900 &&
                    (ae == CaptureResult.CONTROL_AE_STATE_CONVERGED || ae == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED)) break
            }
            // 2) foca na área dos números
            if (canAutoFocus) {
                val trig = builder().apply { set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START) }.build()
                s.capture(trig, cb, handler)
                val afDeadline = SystemClock.elapsedRealtime() + 3000
                while (SystemClock.elapsedRealtime() < afDeadline) {
                    Thread.sleep(100)
                    val af = afState
                    if (af == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED || af == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED) break
                }
            } else {
                Thread.sleep(800)
            }
            readySince.set(SystemClock.elapsedRealtime())
            ready.set(true)
            done.await(10, TimeUnit.SECONDS)
        } finally {
            try { session?.stopRepeating() } catch (_: Exception) {}
            try { session?.close() } catch (_: Exception) {}
            try { device?.close() } catch (_: Exception) {}
            reader.close()
            thread.quitSafely()
        }
        return frames.toList()
    }

    private fun rotate(src: ByteArray, w: Int, h: Int, deg: Int): GrayFrame {
        return when ((deg % 360 + 360) % 360) {
            90 -> {
                val dst = ByteArray(w * h)
                for (y in 0 until h) for (x in 0 until w) dst[x * h + (h - 1 - y)] = src[y * w + x]
                GrayFrame(dst, h, w)
            }
            180 -> {
                val dst = ByteArray(w * h)
                for (i in src.indices) dst[w * h - 1 - i] = src[i]
                GrayFrame(dst, w, h)
            }
            270 -> {
                val dst = ByteArray(w * h)
                for (y in 0 until h) for (x in 0 until w) dst[(w - 1 - x) * h + y] = src[y * w + x]
                GrayFrame(dst, h, w)
            }
            else -> GrayFrame(src, w, h)
        }
    }
}
