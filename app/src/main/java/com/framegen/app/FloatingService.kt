package com.framegen.app

import android.app.*
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.SurfaceView
import android.widget.*
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader
import java.lang.Process
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.content.pm.ServiceInfo

class FloatingService : Service() {

    private lateinit var wm: WindowManager
    private lateinit var mainParams: WindowManager.LayoutParams
    private lateinit var fpsParams: WindowManager.LayoutParams
    private lateinit var fgParams: WindowManager.LayoutParams
    private lateinit var canvasParams: WindowManager.LayoutParams // 🔥 Parameter buat Proyektor

    // UI Components
    private lateinit var rootLayout: FrameLayout
    private lateinit var panelLayout: LinearLayout
    private lateinit var minimizedBox: TextView
    private lateinit var statusText: TextView
    private lateinit var fpsCorner: TextView
    private lateinit var fgCorner: TextView
    private lateinit var switchFG: Switch
    private lateinit var switchFPS: Switch
    private lateinit var overlayCanvas: SurfaceView
    private lateinit var screenshotCounterText: TextView 

    private val handler = Handler(Looper.getMainLooper())
    private var targetPackage = ""
    private var permissionResultCode = 0
    private var permissionDataIntent: Intent? = null

    private var isMinimized = false
    private var isFrameGenOn = false
    private var isDisplayFpsOn = false

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging = false

    private val updateRunnable = object : Runnable {
        override fun run() {
            CoroutineScope(Dispatchers.IO).launch {
                val isActive = checkIfGameActive(targetPackage)
                if (isActive) {
                    val realFPS = getGameFps(targetPackage)
                    val fakeFPS = if (realFPS > 0) realFPS * 2 else 0
                    withContext(Dispatchers.Main) {
                        statusText.text = "Game Active\nReal: $realFPS | Fake: $fakeFPS"
                        statusText.setTextColor(Color.GREEN)
                        if (isDisplayFpsOn) fpsCorner.text = "FPS = $realFPS -> $fakeFPS"
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        statusText.text = "Waiting for game..."
                        statusText.setTextColor(Color.YELLOW)
                        if (isDisplayFpsOn) fpsCorner.text = "FPS = 0 -> 0"
                    }
                }
            }
            handler.postDelayed(this, 1000)
        }
    }

    private fun executeShizukuCommand(cmd: String): Process? {
        try {
            val clazz = Class.forName("rikka.shizuku.Shizuku")
            val methods = clazz.declaredMethods
            for (m in methods) {
                if (m.name == "newProcess" && m.parameterTypes.size == 3) {
                    m.isAccessible = true 
                    return m.invoke(null, arrayOf("sh", "-c", cmd), null, null) as? Process
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
        return null
    }

    private fun checkIfGameActive(pkg: String): Boolean {
        if (pkg.isEmpty()) return false
        var p: Process? = null
        try {
            p = executeShizukuCommand("dumpsys activity activities") ?: return false
            val isActive = BufferedReader(InputStreamReader(p.inputStream)).use { reader ->
                var found = false
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    if (line!!.contains("mResumedActivity") && line!!.contains(pkg)) {
                        found = true
                        break 
                    }
                }
                found
            }
            return isActive
        } catch (e: Exception) { return false } 
        finally { try { p?.destroy() } catch (e: Exception) { e.printStackTrace() } }
    }

    private fun getGameFps(pkg: String): Int {
        if (!Shizuku.pingBinder()) return 0
        val fpsLayer = getFpsFromSurfaceFlinger(pkg)
        if (fpsLayer > 0) return fpsLayer
        val fpsGfx = getFpsFromGfxInfo(pkg)
        if (fpsGfx > 0) return fpsGfx
        return getFpsFromPageFlip()
    }

    private fun getFpsFromSurfaceFlinger(pkg: String): Int {
        try {
            val listP = executeShizukuCommand("dumpsys SurfaceFlinger --list") ?: return 0
            val listReader = BufferedReader(InputStreamReader(listP.inputStream))
            val layers = mutableListOf<String>()
            var l: String?
            while (listReader.readLine().also { l = it } != null) {
                if (l!!.contains(pkg)) layers.add(l!!.trim())
            }
            listP.waitFor()

            var maxFps = 0
            val now = System.nanoTime()
            val oneSecondAgo = now - 1_000_000_000L

            for (layer in layers) {
                val latencyP = executeShizukuCommand("dumpsys SurfaceFlinger --latency '$layer'") ?: continue
                val latencyReader = BufferedReader(InputStreamReader(latencyP.inputStream))
                var fpsCount = 0
                var isFirst = true
                var line: String?
                while (latencyReader.readLine().also { line = it } != null) {
                    if (isFirst) { isFirst = false; continue } 
                    val cols = line!!.trim().split("\\s+".toRegex())
                    if (cols.size >= 2) {
                        val frameTime = cols[1].toLongOrNull()
                        if (frameTime != null && frameTime > oneSecondAgo && frameTime <= now && frameTime != Long.MAX_VALUE) {
                            fpsCount++
                        }
                    }
                }
                latencyP.waitFor()
                if (fpsCount > maxFps) maxFps = fpsCount
            }
            return maxFps
        } catch (e: Exception) { return 0 }
    }

    private fun getFpsFromGfxInfo(pkg: String): Int {
        try {
            val p = executeShizukuCommand("dumpsys gfxinfo $pkg framestats") ?: return 0
            val reader = BufferedReader(InputStreamReader(p.inputStream))
            var validFrames = 0
            val now = System.nanoTime()
            val oneSecondAgo = now - 1_000_000_000L
            var isProfileData = false
            var line: String?
            
            while (reader.readLine().also { line = it } != null) {
                if (line!!.contains("---PROFILEDATA---")) {
                    isProfileData = !isProfileData
                    continue
                }
                if (isProfileData) {
                    val data = line!!.split(",")
                    if (data.size >= 3) {
                        val vSyncTime = data[1].toLongOrNull()
                        if (vSyncTime != null && vSyncTime > oneSecondAgo && vSyncTime <= now) {
                            validFrames++
                        }
                    }
                }
            }
            p.waitFor()
            return validFrames
        } catch (e: Exception) { return 0 }
    }

    private var lastPageFlipCount = -1L
    private var lastCheckTime = 0L

    private fun getFpsFromPageFlip(): Int {
        try {
            val p = executeShizukuCommand("service call SurfaceFlinger 1013") ?: return 0
            val reader = BufferedReader(InputStreamReader(p.inputStream))
            val output = reader.readLine() ?: ""
            p.waitFor()

            val match = Regex("Parcel\\([0-9a-fA-F]+\\s+([0-9a-fA-F]+)").find(output)
            if (match != null) {
                val currentCount = match.groupValues[1].toLong(16)
                val currentTime = System.currentTimeMillis()

                if (lastPageFlipCount == -1L || (currentTime - lastCheckTime) > 2000) {
                    lastPageFlipCount = currentCount
                    lastCheckTime = currentTime
                    return 60 
                }

                val diffFrames = currentCount - lastPageFlipCount
                val diffTime = currentTime - lastCheckTime

                lastPageFlipCount = currentCount
                lastCheckTime = currentTime

                if (diffTime > 0) {
                    val fps = (diffFrames * 1000f / diffTime).toInt()
                    return fps.coerceIn(0, 144)
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
        return 0
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let {
            targetPackage = it.getStringExtra("TARGET_PACKAGE") ?: ""
            permissionResultCode = it.getIntExtra("RESULT_CODE", 0)
            permissionDataIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                it.getParcelableExtra("DATA_INTENT", Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                it.getParcelableExtra("DATA_INTENT")
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundServiceProperly()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        setupFloatingUI()
        handler.post(updateRunnable)
    }

    private fun setupFloatingUI() {
        // 🔥 Layout Panel UI (Kecil melayang)
        mainParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 50; y = 150 }

        // 🔥 Layout Kanvas Proyektor (Full Screen & Tembus Jari)
        canvasParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }

        canvasParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        val createCornerParams = { gravity: Int ->
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            ).apply { this.gravity = gravity }
        }
        fpsParams = createCornerParams(Gravity.TOP or Gravity.START)
        fgParams = createCornerParams(Gravity.TOP or Gravity.END)

        rootLayout = FrameLayout(this)
        
        // 🔥 Inisialisasi Proyektor (TAPI JANGAN DIMASUKIN KE ROOTLAYOUT LAGI!)
        overlayCanvas = SurfaceView(this)
        overlayCanvas.setZOrderOnTop(true)
        overlayCanvas.setZOrderMediaOverlay(true)
        overlayCanvas.setSecure(true)
        overlayCanvas.setZOrderMediaOverlay(true)
        overlayCanvas.holder.setFormat(PixelFormat.TRANSLUCENT)
        overlayCanvas.setBackgroundColor(Color.TRANSPARENT)

        overlayCanvas.holder.addCallback(object : android.view.SurfaceHolder.Callback {
            override fun surfaceCreated(holder: android.view.SurfaceHolder) {
                NativeBridge.setSurface(holder.surface)
            }
            override fun surfaceChanged(holder: android.view.SurfaceHolder, format: Int, w: Int, h: Int) {}
            override fun surfaceDestroyed(holder: android.view.SurfaceHolder) {
                NativeBridge.setSurface(null)
            }
        })

        panelLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#CC000000"))
                cornerRadius = 32f
            }
        }

        // 🔥 BIKIN KOTAK MINIMIZE JADI BULAT DAN RAPI
        minimizedBox = TextView(this).apply {
            text = "FG"
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(32, 32, 32, 32)
            background = GradientDrawable().apply { 
                setColor(Color.parseColor("#CC000000"))
                cornerRadius = 100f // Bikin Bulat
            }
            visibility = View.GONE
            // Kunci ukurannya biar ga ngaco
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 16)
        }
        val title = TextView(this).apply {
            text = "FrameGen Panel"
            setTextColor(Color.WHITE)
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val minBtn = Button(this).apply {
            text = "—"
            setTextColor(Color.WHITE)
            background = null
            textSize = 18f
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            setOnClickListener {
                isMinimized = true
                panelLayout.visibility = View.GONE
                minimizedBox.visibility = View.VISIBLE
                wm.updateViewLayout(rootLayout, mainParams)
            }
        }
        header.addView(title)
        header.addView(minBtn)

        switchFG = Switch(this).apply {
            text = "Start FrameGen"
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(0, 8, 0, 8)
            setOnCheckedChangeListener { _, isChecked ->
                isFrameGenOn = isChecked
                fgCorner.visibility = if (isChecked) View.VISIBLE else View.GONE
                
                if (isChecked) {
                    screenshotCounterText.visibility = View.VISIBLE
                    screenshotCounterText.text = "Screenshot: 0"
                    if (permissionDataIntent != null) {
                        FrameGenEngine.startEngine(this@FloatingService, permissionResultCode, permissionDataIntent!!) { count ->
                            handler.post { screenshotCounterText.text = "Screenshot: $count" }
                        }
                    } else {
                        Toast.makeText(this@FloatingService, "Error: Tidak ada izin rekam layar", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    screenshotCounterText.visibility = View.GONE
                    FrameGenEngine.stopEngine()
                }
            }
        }

        switchFPS = Switch(this).apply {
            text = "Display FPS"
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(0, 8, 0, 8)
            setOnCheckedChangeListener { _, isChecked ->
                isDisplayFpsOn = isChecked
                fpsCorner.visibility = if (isChecked) View.VISIBLE else View.GONE
            }
        }

        statusText = TextView(this).apply {
            text = "Please enter the game first"
            setTextColor(Color.YELLOW)
            textSize = 12f
            setPadding(0, 16, 0, 0)
        }

        screenshotCounterText = TextView(this).apply {
            text = "Screenshot: 0"
            setTextColor(Color.CYAN)
            textSize = 12f
            setPadding(0, 8, 0, 0)
            visibility = View.GONE 
        }

        panelLayout.addView(header)
        panelLayout.addView(switchFG)
        panelLayout.addView(switchFPS)
        panelLayout.addView(statusText)
        panelLayout.addView(screenshotCounterText) 

        rootLayout.addView(panelLayout)
        rootLayout.addView(minimizedBox)

        fpsCorner = TextView(this).apply {
            text = "FPS = 0 -> 0"
            setTextColor(Color.GREEN)
            textSize = 14f
            setBackgroundColor(Color.parseColor("#80000000"))
            setPadding(12, 8, 12, 8)
            visibility = View.GONE
        }
        fgCorner = TextView(this).apply {
            text = "FrameGEN: ON"
            setTextColor(Color.CYAN)
            textSize = 14f
            setBackgroundColor(Color.parseColor("#80000000"))
            setPadding(12, 8, 12, 8)
            visibility = View.GONE
        }

        val dragTouchListener = View.OnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = mainParams.x
                    initialY = mainParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) isDragging = true
                    mainParams.x = initialX + dx.toInt()
                    mainParams.y = initialY + dy.toInt()
                    wm.updateViewLayout(rootLayout, mainParams)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging && v == minimizedBox) {
                        isMinimized = false
                        panelLayout.visibility = View.VISIBLE
                        minimizedBox.visibility = View.GONE
                        wm.updateViewLayout(rootLayout, mainParams)
                    }
                    true
                }
                else -> false
            }
        }

        header.setOnTouchListener(dragTouchListener)
        minimizedBox.setOnTouchListener(dragTouchListener)

        try {
            // 🔥 SOLUSI BUG: Proyektor dipasang terpisah dari Panel UI!
            wm.addView(overlayCanvas, canvasParams) 
            wm.addView(rootLayout, mainParams)
            wm.addView(fpsCorner, fpsParams)
            wm.addView(fgCorner, fgParams)
        } catch (e: Exception) {
            e.printStackTrace()
            stopSelf()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(updateRunnable)
        try { wm.removeView(overlayCanvas) } catch (_: Exception) {} 
        try { wm.removeView(rootLayout) } catch (_: Exception) {}
        try { wm.removeView(fpsCorner) } catch (_: Exception) {}
        try { wm.removeView(fgCorner) } catch (_: Exception) {}
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
                .setContentText("Overlay aktif membaca Game FPS & Merekam Layar")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .build()
            
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else {
                startForeground(1, notification)
            }
        }
    }
}