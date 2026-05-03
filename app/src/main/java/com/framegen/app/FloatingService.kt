package com.framegen.app

import android.app.*
import android.content.Intent
import android.graphics.PixelFormat
import android.os.*
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import kotlin.random.Random

class FloatingService : Service() {

    private lateinit var wm: WindowManager
    private lateinit var text: TextView
    private val handler = Handler(Looper.getMainLooper())

    private val updateRunnable = object : Runnable {
        override fun run() {

            val realFPS = Random.nextInt(45, 61)
            val fakeFPS = realFPS * 2

            text.text = "Real FPS: $realFPS\nFake FPS: $fakeFPS"

            handler.postDelayed(this, 500)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()

        startForegroundServiceProperly()

        wm = getSystemService(WINDOW_SERVICE) as WindowManager

        text = TextView(this)
        text.text = "Starting..."
        text.textSize = 16f

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )

        params.gravity = Gravity.TOP or Gravity.START
        params.x = 50
        params.y = 150

        try {
            wm.addView(text, params)
        } catch (e: Exception) {
            e.printStackTrace()
            stopSelf()
            return
        }

        handler.post(updateRunnable)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()

        handler.removeCallbacks(updateRunnable)

        if (::text.isInitialized) {
            try {
                wm.removeView(text)
            } catch (_: Exception) {}
        }
    }

    private fun startForegroundServiceProperly() {
        val channelId = "framegen_channel"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            val channel = NotificationChannel(
                channelId,
                "FrameGen Service",
                NotificationManager.IMPORTANCE_LOW
            )

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)

            val notification = Notification.Builder(this, channelId)
                .setContentTitle("FrameGen Running")
                .setContentText("Overlay aktif")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .build()

            startForeground(1, notification)
        }
    }
}