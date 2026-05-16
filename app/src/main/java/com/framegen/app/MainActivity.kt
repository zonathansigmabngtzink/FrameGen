package com.framegen.app

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.switchmaterial.SwitchMaterial
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {

    private lateinit var shizukuStatus: TextView
    private lateinit var overlayStatus: TextView
    private lateinit var frameSwitch: SwitchMaterial
    private lateinit var gamePackageInput: EditText

    // 🔥 Variabel untuk Minta Izin Rekam Layar
    private lateinit var mediaProjectionManager: MediaProjectionManager

    private val handler = Handler(Looper.getMainLooper())

    private var isRunning = false
    private var isUserAction = true

    // 🔥 Penangkap Izin Rekam Layar dari User
    private val screenCaptureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            // Jika user klik "Mulai Sekarang", kirim data ke Service
            startFloatingServiceWithPermission(result.resultCode, result.data!!)
        } else {
            Toast.makeText(this, "Izin rekam layar ditolak!", Toast.LENGTH_SHORT).show()
            safeSwitchOff()
        }
    }

    private val shizukuListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == 1001 && grantResult == PackageManager.PERMISSION_GRANTED) {
                startFrameGen()
            }
        }

    private val statusRunnable = object : Runnable {
        override fun run() {
            updateStatus()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        shizukuStatus = findViewById(R.id.shizukuStatus)
        overlayStatus = findViewById(R.id.overlayStatus)
        frameSwitch = findViewById(R.id.frameSwitch)
        gamePackageInput = findViewById(R.id.gamePackageInput)

        // Inisialisasi MediaProjection
        mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        // Membaca nama game yang tersimpan
        val sharedPref = getSharedPreferences("FrameGenPrefs", MODE_PRIVATE)
        val savedGame = sharedPref.getString("LAST_GAME", "")
        gamePackageInput.setText(savedGame)

        Shizuku.addBinderReceivedListener { runOnUiThread { updateStatus() } }
        Shizuku.addBinderDeadListener { runOnUiThread { updateStatus() } }
        Shizuku.addRequestPermissionResultListener(shizukuListener)

        frameSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (!isUserAction) return@setOnCheckedChangeListener
            if (isChecked) tryStart() else stopFrameGen()
        }
    }

    override fun onResume() {
        super.onResume()
        handler.postDelayed(statusRunnable, 500)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(statusRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeRequestPermissionResultListener(shizukuListener)
    }

    private fun tryStart() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            safeSwitchOff()
            return
        }

        val running = try {
            Shizuku.pingBinder() || Shizuku.getUid() != -1
        } catch (e: Exception) { false }

        if (!running) {
            safeSwitchOff()
            return
        }

        val perm = try { Shizuku.checkSelfPermission() } catch (e: Exception) { PackageManager.PERMISSION_DENIED }

        if (perm != PackageManager.PERMISSION_GRANTED) {
            Shizuku.requestPermission(1001)
            return
        }

        startFrameGen()
    }

    // 🔥 Fungsi ini sekarang tugasnya MINTA IZIN layar dulu
    private fun startFrameGen() {
        if (isRunning) return

        val targetPkg = gamePackageInput.text.toString().trim()
        if (targetPkg.isEmpty()) {
            Toast.makeText(this, "Isi nama package game dulu! (Contoh: com.mobile.legends)", Toast.LENGTH_SHORT).show()
            safeSwitchOff()
            return
        }

        // Simpan nama game
        getSharedPreferences("FrameGenPrefs", MODE_PRIVATE)
            .edit()
            .putString("LAST_GAME", targetPkg)
            .apply()

        // Panggil Pop-Up Rekam Layar
        screenCaptureLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
    }

    // 🔥 Fungsi BARU untuk menyalakan Service setelah dapat izin
    private fun startFloatingServiceWithPermission(resultCode: Int, data: Intent) {
        isRunning = true
        frameSwitch.text = "Frame Generation ON"

        val targetPkg = gamePackageInput.text.toString().trim()
        
        val intent = Intent(this, FloatingService::class.java).apply {
            putExtra("TARGET_PACKAGE", targetPkg)
            putExtra("RESULT_CODE", resultCode) // Mengirim tiket izin ke Service
            putExtra("DATA_INTENT", data)       // Mengirim data izin ke Service
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            safeSwitchOff()
            return
        }
        
        // NativeBridge.startFrameGen() tidak dipanggil di sini lagi,
        // Nanti dipanggil oleh FrameGenEngine.kt dari FloatingService.
    }

    private fun stopFrameGen() {
        if (!isRunning) return
        isRunning = false
        frameSwitch.text = "Frame Generation OFF"
        try { stopService(Intent(this, FloatingService::class.java)) } catch (e: Exception) { e.printStackTrace() }
        
    }

    private fun safeSwitchOff() {
        isUserAction = false
        frameSwitch.isChecked = false
        isUserAction = true
    }

    private fun updateStatus() {
        val shizukuRunning = try { Shizuku.pingBinder() || Shizuku.getUid() != -1 } catch (e: Exception) { false }
        val shizukuPerm = try { if (shizukuRunning) Shizuku.checkSelfPermission() else PackageManager.PERMISSION_DENIED } catch (e: Exception) { PackageManager.PERMISSION_DENIED }

        when {
            !shizukuRunning -> {
                shizukuStatus.text = "Shizuku: OFF"
                shizukuStatus.setTextColor(Color.RED)
            }
            shizukuPerm == PackageManager.PERMISSION_GRANTED -> {
                shizukuStatus.text = "Shizuku: READY"
                shizukuStatus.setTextColor(Color.GREEN)
            }
            else -> {
                shizukuStatus.text = "Shizuku: NEED PERMISSION"
                shizukuStatus.setTextColor(Color.YELLOW)
            }
        }

        val overlay = Settings.canDrawOverlays(this)
        if (overlay) {
            overlayStatus.text = "Overlay: ENABLED"
            overlayStatus.setTextColor(Color.GREEN)
        } else {
            overlayStatus.text = "Overlay: DISABLED"
            overlayStatus.setTextColor(Color.RED)
        }
    }
}