package com.framegen.app

import android.view.Surface
import java.nio.ByteBuffer
import android.hardware.HardwareBuffer

object NativeBridge {
    init {
        try {
            System.loadLibrary("framegen")
        } catch (e: UnsatisfiedLinkError) {
            e.printStackTrace()
        }
    }

    @JvmStatic
    external fun setSurface(surface: Surface?)

    @JvmStatic
    external fun pushHardwareBuffer(
        hardwareBuffer: HardwareBuffer,
        width: Int,
        height: Int
    )

    @JvmStatic
    external fun startFrameGen()

    @JvmStatic
    external fun stopFrameGen()
}