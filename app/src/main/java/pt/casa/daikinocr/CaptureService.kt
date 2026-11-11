package pt.casa.daikinocr

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager

/**
 * Serviço em primeiro plano (tipo câmara) que mantém o CPU acordado e fotografa a cada N minutos,
 * mesmo com o ecrã desligado.
 */
class CaptureService : Service() {

    companion object {
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val ACTION_NOW = "now"
        private const val CHANNEL = "daikin_ocr"
        private const val NOTIF_ID = 46

        fun start(ctx: Context, action: String = ACTION_START) {
            val i = Intent(ctx, CaptureService::class.java).setAction(action)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }
    }

    private lateinit var prefs: Prefs
    private lateinit var engine: Engine
    private var worker: HandlerThread? = null
    private var handler: Handler? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val loop = object : Runnable {
        override fun run() {
            val status = engine.runCycle()
            updateNotification(status)
            handler?.postDelayed(this, prefs.intervalMin * 60_000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        engine = Engine(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action ?: ACTION_START) {
            ACTION_STOP -> {
                try { goForeground("A parar…") } catch (_: Exception) {}
                prefs.running = false
                stopLoop()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_NOW -> {
                goForeground("A fotografar…")
                if (handler == null) {
                    // leitura única: fotografa, envia e termina
                    Thread {
                        val st = engine.runCycle()
                        updateNotification(st)
                        if (!prefs.running) {
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            stopSelf()
                        }
                    }.start()
                } else {
                    handler?.removeCallbacks(loop)
                    handler?.post(loop)
                }
            }
            else -> {
                goForeground("A iniciar…")
                prefs.running = true
                if (handler == null) startLoop()
            }
        }
        return START_STICKY
    }

    private fun startLoop() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DaikinOCR::captura").apply {
            setReferenceCounted(false)
            acquire()
        }
        worker = HandlerThread("daikin-worker").apply { start() }
        handler = Handler(worker!!.looper)
        handler!!.post(loop)
    }

    private fun stopLoop() {
        handler?.removeCallbacksAndMessages(null)
        worker?.quitSafely()
        handler = null
        worker = null
        try { wakeLock?.release() } catch (_: Exception) {}
        wakeLock = null
    }

    override fun onDestroy() {
        stopLoop()
        super.onDestroy()
    }

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Leitura Daikin", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("Daikin OCR — a cada ${prefs.intervalMin} min")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    private fun goForeground(text: String) {
        val n = buildNotification(text)
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
            } else {
                startForeground(NOTIF_ID, n)
            }
        } catch (e: Exception) {
            // Android não deixa iniciar a câmara em segundo plano (ex.: após reinício): é preciso abrir o app.
            prefs.lastStatus = "Parado: abra o app e toque em Iniciar (${e.javaClass.simpleName})"
            stopSelf()
        }
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }
}
