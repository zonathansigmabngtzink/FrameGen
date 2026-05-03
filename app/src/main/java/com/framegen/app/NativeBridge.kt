package com.framegen.app

object NativeBridge {
    init {
        try {
            System.loadLibrary("framegen")
        } catch (e: UnsatisfiedLinkError) {
            e.printStackTrace()
        }
    }

    @JvmStatic
    external fun startFrameGen()

    @JvmStatic
    external fun stopFrameGen()
}