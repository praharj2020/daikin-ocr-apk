package pt.casa.daikinocr

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var engine: Engine
    private lateinit var roiView: RoiView
    private lateinit var status: TextView
    private lateinit var history: TextView
    private lateinit var calibResult: TextView
    private lateinit var url: EditText
    private lateinit var interval: EditText
    private lateinit var zoom: EditText
    private lateinit var geminiKey: EditText
    private lateinit var geminiModel: EditText
    private lateinit var version: TextView
    private var calibFrame: GrayFrame? = null
    private val ui = Handler(Looper.getMainLooper())

    private val refresher = object : Runnable {
        override fun run() {
            refresh()
            ui.postDelayed(this, 3000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)
        engine = Engine(this)

        roiView = findViewById(R.id.roiView)
        status = findViewById(R.id.status)
        history = findViewById(R.id.history)
        calibResult = findViewById(R.id.calibResult)
        url = findViewById(R.id.url)
        interval = findViewById(R.id.interval)
        zoom = findViewById(R.id.zoom)
        geminiKey = findViewById(R.id.geminiKey)
        geminiModel = findViewById(R.id.geminiModel)
        version = findViewById(R.id.version)
        geminiKey.setText(prefs.geminiKey)
        geminiModel.setText(prefs.geminiModel)
        version.text = "Versão ${BuildConfig.VERSION_NAME}"

        url.setText(prefs.webhookUrl)
        interval.setText(prefs.intervalMin.toString())
        zoom.setText(prefs.zoom.toString())
        roiView.roi = prefs.roi
        if (engine.frameFile().exists()) roiView.bitmap = BitmapFactory.decodeFile(engine.frameFile().absolutePath)

        roiView.onRoiChanged = { r ->
            prefs.roi = r
            testCalibration()
        }

        findViewById<Button>(R.id.btnPhoto).setOnClickListener { takeCalibrationPhoto(false) }
        findViewById<Button>(R.id.btnPhotoFlash).setOnClickListener { takeCalibrationPhoto(true) }
        findViewById<Button>(R.id.btnSave).setOnClickListener { saveSettings() }
        findViewById<Button>(R.id.btnStart).setOnClickListener {
            saveSettings()
            if (prefs.roi == null) { toast("Primeiro marque a área dos números na foto."); return@setOnClickListener }
            CaptureService.start(this)
            toast("Monitorização iniciada. Pode desligar o ecrã.")
        }
        findViewById<Button>(R.id.btnNow).setOnClickListener {
            if (prefs.roi == null) { toast("Primeiro marque a área dos números na foto."); return@setOnClickListener }
            CaptureService.start(this, CaptureService.ACTION_NOW)
            toast("A ligar as tomadas; foto daqui a 1 minuto…")
        }
        findViewById<Button>(R.id.btnStop).setOnClickListener {
            prefs.running = false
            stopService(Intent(this, CaptureService::class.java))
            toast("Monitorização parada.")
        }
        findViewById<Button>(R.id.btnBattery).setOnClickListener { askBattery() }

        askPermissions()
    }

    override fun onResume() {
        super.onResume()
        ui.post(refresher)
        checkUpdate()
    }

    /** A cada abertura: procura versão nova e instala. */
    private fun checkUpdate() {
        Thread {
            val r = Updater.fetchRemote()
            if (r != null && r.code > BuildConfig.VERSION_CODE && Build.VERSION.SDK_INT >= 26 &&
                !packageManager.canRequestPackageInstalls()) {
                runOnUiThread {
                    version.text = "Versão ${BuildConfig.VERSION_NAME} — nova ${r.name} disponível: autorize a instalação"
                    toast("Autorize 'Instalar apps desconhecidas' para atualizar")
                    startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                }
                return@Thread
            }
            val st = Updater.checkAndInstall(this)
            runOnUiThread { version.text = st }
        }.start()
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(refresher)
    }

    private fun refresh() {
        val pm = getSystemService(PowerManager::class.java)
        val bat = if (pm.isIgnoringBatteryOptimizations(packageName)) "sem restrição ✔" else "RESTRITA ✖ (toque no botão Bateria)"
        status.text = "Estado: ${if (prefs.running) "A MONITORIZAR a cada ${prefs.intervalMin} min" else "parado"}\n" +
            "Última: ${prefs.lastStatus}\nBateria: $bat"
        history.text = prefs.history
    }

    private fun saveSettings() {
        prefs.webhookUrl = url.text.toString()
        prefs.intervalMin = interval.text.toString().toIntOrNull() ?: 10
        prefs.zoom = zoom.text.toString().replace(',', '.').toFloatOrNull() ?: 1f
        prefs.geminiKey = geminiKey.text.toString()
        prefs.geminiModel = geminiModel.text.toString()
        toast("Definições guardadas")
    }

    private fun takeCalibrationPhoto(flash: Boolean) {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            askPermissions(); return
        }
        saveSettings()
        calibResult.text = "A fotografar…"
        Thread {
            try {
                val frames = synchronized(Engine.lock) {
                    CameraGrabber(this).grab(flash, count = 1, zoom = prefs.zoom, roi = prefs.roi)
                }
                val f = frames.firstOrNull() ?: throw IllegalStateException("sem imagem")
                calibFrame = f
                engine.saveFrame(f)
                val bmp = engine.toBitmap(f)
                runOnUiThread {
                    roiView.bitmap = bmp
                    calibResult.text = "Arraste o dedo para desenhar uma caixa à volta dos 2 números (com uma pequena folga)."
                    if (prefs.roi != null) testCalibration()
                }
            } catch (e: Exception) {
                runOnUiThread { calibResult.text = "Erro na câmara: ${e.message}" }
            }
        }.start()
    }

    private fun testCalibration() {
        val f = calibFrame
        if (f == null) {
            calibResult.text = "Área guardada. Tire uma foto para testar."
            return
        }
        val roi = engine.roiFor(f) ?: return
        val r = SevenSegOcr.read(f.data, f.width, f.height, roi)
        val local = if (r.value != null) "${r.value} °C" else "não leu"
        calibResult.text = "Área guardada. Local: $local" + if (prefs.geminiKey.isNotBlank()) " · Gemini: a ler…" else ""
        if (prefs.geminiKey.isBlank()) return
        Thread {
            val crop = engine.cropBitmap(f)
            val g = if (crop != null) GeminiReader.read(crop, prefs.geminiKey, prefs.geminiModel) else null
            val gtxt = when {
                g == null -> "sem recorte"
                g.value != null -> "${g.value} °C ✔"
                g.error != null -> "erro: ${g.error}"
                else -> "não leu (${g.raw.take(20)})"
            }
            runOnUiThread { calibResult.text = "Área guardada. Local: $local · Gemini: $gtxt" }
        }.start()
    }

    private fun askPermissions() {
        val need = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) need += Manifest.permission.CAMERA
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            need += Manifest.permission.POST_NOTIFICATIONS
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), 1)
    }

    @Suppress("BatteryLife")
    private fun askBattery() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
