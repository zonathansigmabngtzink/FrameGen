package com.framegen.app

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.switchmaterial.SwitchMaterial
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {

    private lateinit var shizukuStatus: TextView
    private lateinit var overlayStatus: TextView
    private lateinit var frameSwitch: SwitchMaterial

    private val handler = Handler(Looper.getMainLooper())

    private var isRunning = false
    private var isUserAction = true

    private val shizukuListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == 1001 &&
                grantResult == PackageManager.PERMISSION_GRANTED) {

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

        // 🔥 LISTENER WAJIB (INI YANG BIKIN KEDETECT)
        Shizuku.addBinderReceivedListener {
            runOnUiThread { updateStatus() }
        }

        Shizuku.addBinderDeadListener {
            runOnUiThread { updateStatus() }
        }

        Shizuku.addRequestPermissionResultListener(shizukuListener)

        frameSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (!isUserAction) return@setOnCheckedChangeListener

            if (isChecked) tryStart()
            else stopFrameGen()
        }
    }

    override fun onResume() {
        super.onResume()
        handler.postDelayed(statusRunnable, 500) // delay biar binder attach dulu
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

        // Overlay check
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            ))
            safeSwitchOff()
            return
        }

        // 🔥 DETEKSI LEBIH AKURAT
        val running = try {
            Shizuku.pingBinder() || Shizuku.getUid() != -1
        } catch (e: Exception) {
            false
        }

        if (!running) {
            safeSwitchOff()
            return
        }

        val perm = try {
            Shizuku.checkSelfPermission()
        } catch (e: Exception) {
            PackageManager.PERMISSION_DENIED
        }

        if (perm != PackageManager.PERMISSION_GRANTED) {
            Shizuku.requestPermission(1001)
            return // ❗ jangan matiin switch
        }

        startFrameGen()
    }

    private fun startFrameGen() {
        if (isRunning) return

        isRunning = true
        frameSwitch.text = "Frame Generation ON"

        val intent = Intent(this, FloatingService::class.java)

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

        Thread {
            try {
                NativeBridge.startFrameGen()
            } catch (e: Throwable) {
                e.printStackTrace()
                runOnUiThread { safeSwitchOff() }
            }
        }.start()
    }

    private fun stopFrameGen() {
        if (!isRunning) return

        isRunning = false
        frameSwitch.text = "Frame Generation OFF"

        try {
            stopService(Intent(this, FloatingService::class.java))
        } catch (e: Exception) {
            e.printStackTrace()
        }

        try {
            NativeBridge.stopFrameGen()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun safeSwitchOff() {
        isUserAction = false
        frameSwitch.isChecked = false
        isUserAction = true
    }

    private fun updateStatus() {

        val shizukuRunning = try {
            Shizuku.pingBinder() || Shizuku.getUid() != -1
        } catch (e: Exception) {
            false
        }

        val shizukuPerm = try {
            if (shizukuRunning) Shizuku.checkSelfPermission()
            else PackageManager.PERMISSION_DENIED
        } catch (e: Exception) {
            PackageManager.PERMISSION_DENIED
        }

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