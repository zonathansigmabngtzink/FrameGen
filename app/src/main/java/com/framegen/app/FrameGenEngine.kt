package com.framegen.app

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.WindowManager

object FrameGenEngine {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var isEngineRunning = false

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    // Counter UI
    private var frameCount = 0

    // FPS limiter native push
    private var lastFramePushTime = 0L

    fun startEngine(
        context: Context,
        resultCode: Int,
        data: Intent,
        onFrameCaptured: (Int) -> Unit
    ) {

        if (isEngineRunning) return

        isEngineRunning = true

        frameCount = 0
        lastFramePushTime = 0L

        Log.i("FrameGen", "Menyalakan Mesin FrameGen...")

        NativeBridge.startFrameGen()

        backgroundThread = HandlerThread("FrameGenThread").apply {
            start()
        }

        backgroundHandler = Handler(backgroundThread!!.looper)

        val mediaProjectionManager =
            context.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                    as MediaProjectionManager

        mediaProjection =
            mediaProjectionManager.getMediaProjection(
                resultCode,
                data
            )

        val wm =
            context.getSystemService(Context.WINDOW_SERVICE)
                    as WindowManager

        // =========================================
        // AMBIL UKURAN DISPLAY YANG BENAR
        // =========================================

        val width: Int
        val height: Int
        val density: Int

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {

            val bounds = wm.currentWindowMetrics.bounds
            val rotation = wm.defaultDisplay.rotation
            Log.i("FrameGen", "Rotation = $rotation")
            width = bounds.width()
            height = bounds.height()

            density =
                context.resources.displayMetrics.densityDpi

        } else {

            @Suppress("DEPRECATION")
            val metrics =
                context.resources.displayMetrics

            width = metrics.widthPixels
            height = metrics.heightPixels
            density = metrics.densityDpi
        }

        Log.i(
            "FrameGen",
            "Display Size = ${width}x${height}"
        )

        // =========================================
        // IMAGE READER
        // =========================================
        imageReader =
            ImageReader.newInstance(
                width,
                height,
                PixelFormat.RGBA_8888,
                3,
                android.hardware.HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or
                android.hardware.HardwareBuffer.USAGE_GPU_COLOR_OUTPUT
            )


        imageReader?.setOnImageAvailableListener(
            { reader ->

                val image = try {
                    reader.acquireLatestImage()
                } catch (e: Exception) {
                    null
                }

                if (image == null) {
                    return@setOnImageAvailableListener
                }

                try {

                    frameCount++

                    onFrameCaptured(frameCount)

                    val hardwareBuffer = image.hardwareBuffer

                    // =========================================
                    // FPS LIMITER
                    // =========================================

                    val now = System.nanoTime()

                    // ~120fps max push
                    if (now - lastFramePushTime >= 8_000_000L) {

                        lastFramePushTime = now

                        if (hardwareBuffer != null) {

                            NativeBridge.pushHardwareBuffer(
                                hardwareBuffer,
                                image.width,
                                image.height
                            )

                            hardwareBuffer.close()
                        }

                    }

                } catch (e: Exception) {

                    e.printStackTrace()

                } finally {

                    try {
                        image.close()
                    } catch (_: Exception) {
                    }
                }

            },
            backgroundHandler
        )

        // =========================================
        // VIRTUAL DISPLAY
        // =========================================

        virtualDisplay =
            mediaProjection?.createVirtualDisplay(
                "FrameGenScreen",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC,
                imageReader?.surface,
                null,
                backgroundHandler
            )

        Log.i("FrameGen", "VirtualDisplay berhasil dibuat")
    }

    fun stopEngine() {

        Log.i("FrameGen", "Mematikan Mesin FrameGen...")

        isEngineRunning = false

        frameCount = 0
        lastFramePushTime = 0L

        try {
            virtualDisplay?.release()
        } catch (_: Exception) {
        }

        try {
            imageReader?.close()
        } catch (_: Exception) {
        }

        try {
            mediaProjection?.stop()
        } catch (_: Exception) {
        }

        virtualDisplay = null
        imageReader = null
        mediaProjection = null

        try {
            backgroundThread?.quitSafely()
        } catch (_: Exception) {
        }

        backgroundThread = null
        backgroundHandler = null

        NativeBridge.stopFrameGen()
    }
}