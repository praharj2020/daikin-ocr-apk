package pt.casa.daikinocr

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Auto-atualização a partir do branch "apk" do repositório público. */
object Updater {
    private const val TAG = "Updater"
    const val VERSION_URL = "https://raw.githubusercontent.com/praharj2020/daikin-ocr-apk/apk/version.json"
    private const val CHANNEL = "daikin_update"

    data class Remote(val code: Int, val name: String, val apk: String)

    fun fetchRemote(): Remote? = try {
        val c = URL(VERSION_URL + "?t=" + System.currentTimeMillis()).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 15000
        c.setRequestProperty("Cache-Control", "no-cache")
        val txt = c.inputStream.bufferedReader().readText()
        c.disconnect()
        val j = JSONObject(txt)
        Remote(j.getInt("versionCode"), j.optString("versionName"), j.getString("apk"))
    } catch (e: Exception) {
        Log.w(TAG, "sem versão remota", e); null
    }

    /** Verifica e instala se houver versão nova. Devolve texto de estado. */
    fun checkAndInstall(ctx: Context): String {
        Prefs(ctx).lastUpdateCheck = System.currentTimeMillis()
        val r = fetchRemote() ?: return "Atualização: sem ligação"
        if (r.code <= BuildConfig.VERSION_CODE) return "Versão ${BuildConfig.VERSION_NAME} (atual)"
        if (Build.VERSION.SDK_INT >= 26 && !ctx.packageManager.canRequestPackageInstalls()) {
            return "Nova versão ${r.name}: autorize 'Instalar apps desconhecidas' para o Daikin OCR"
        }
        return try {
            val f = File(ctx.cacheDir, "update.apk")
            val c = URL(r.apk + "?t=" + System.currentTimeMillis()).openConnection() as HttpURLConnection
            c.connectTimeout = 20000; c.readTimeout = 60000
            c.inputStream.use { inp -> f.outputStream().use { inp.copyTo(it) } }
            c.disconnect()
            install(ctx, f)
            "A instalar versão ${r.name}…"
        } catch (e: Exception) {
            Log.e(TAG, "falha a atualizar", e)
            "Atualização falhou: ${e.message}"
        }
    }

    private fun install(ctx: Context, apk: File) {
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            s.openWrite("daikin-ocr.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                s.fsync(out)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(ctx, id, Intent(ctx, InstallReceiver::class.java), flags)
            s.commit(pending.intentSender)
        }
    }

    fun notifyConfirm(ctx: Context, confirm: Intent) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Atualizações", NotificationManager.IMPORTANCE_HIGH))
        }
        val pi = PendingIntent.getActivity(ctx, 7, confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(ctx, CHANNEL)
            .setContentTitle("Daikin OCR: nova versão")
            .setContentText("Toque para instalar a atualização")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify(47, n)
    }
}

/** Recebe o resultado da instalação. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                try {
                    ctx.startActivity(Intent(confirm).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: Exception) {}
                Updater.notifyConfirm(ctx, confirm)
            }
            PackageInstaller.STATUS_SUCCESS -> Prefs(ctx).lastStatus = "Atualizado"
            else -> Prefs(ctx).lastStatus = "Atualização falhou: " + (intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "")
        }
    }
}

/** Depois de atualizar, retoma a monitorização. */
class PackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED && Prefs(ctx).running) {
            try { CaptureService.start(ctx) } catch (e: Exception) {
                Prefs(ctx).lastStatus = "Atualizado: abra o app e toque em Iniciar"
            }
        }
    }
}
