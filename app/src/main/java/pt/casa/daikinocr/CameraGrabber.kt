package pt.casa.daikinocr

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
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

    /**
     * Abre a câmara, deixa estabilizar a exposição/foco e devolve [count] fotogramas.
     * Bloqueia a thread que chama (não chamar na thread principal).
     */
    @SuppressLint("MissingPermission")
    fun grab(flash: Boolean, count: Int = 3, zoom: Float = 1f): List<GrayFrame> {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = backCameraId(cm)
        val ch = cm.getCameraCharacteristics(id)
        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        val sizes = map.getOutputSizes(ImageFormat.YUV_420_888)
        val size: Size = sizes.filter { it.width <= 2400 && it.height <= 1800 }
            .maxByOrNull { it.width.toLong() * it.height } ?: sizes.minByOrNull { it.width.toLong() * it.height }!!
        val rotation = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val hasFlash = ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        val activeArray = ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)

        val thread = HandlerThread("cam").apply { start() }
        val handler = Handler(thread.looper)
        val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 4)

        val frames = ArrayList<GrayFrame>()
        val done = CountDownLatch(1)
        var device: CameraDevice? = null
        var session: CameraCaptureSession? = null
        val warmupMs = if (flash) 2500L else 2000L
        val start = SystemClock.elapsedRealtime()
        var lastTaken = 0L

        reader.setOnImageAvailableListener({ r ->
            val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val now = SystemClock.elapsedRealtime()
                if (frames.size < count && now - start > warmupMs && now - lastTaken > 250) {
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

            val req = cam.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(reader.surface)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                if (flash && hasFlash) set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
                else set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                if (zoom > 1.01f && activeArray != null) {
                    val cw = (activeArray.width() / zoom).toInt()
                    val chh = (activeArray.height() / zoom).toInt()
                    val cx = activeArray.centerX()
                    val cy = activeArray.centerY()
                    set(CaptureRequest.SCALER_CROP_REGION, android.graphics.Rect(cx - cw / 2, cy - chh / 2, cx + cw / 2, cy + chh / 2))
                }
            }.build()
            session!!.setRepeatingRequest(req, null, handler)
            done.await(15, TimeUnit.SECONDS)
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
                // novo: largura h, altura w
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
