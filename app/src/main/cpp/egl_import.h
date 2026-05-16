#pragma once

// Wajib diletakkan sebelum include header egl
#define EGL_EGLEXT_PROTOTYPES

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <android/hardware_buffer.h>

EGLImageKHR createEGLImageFromBuffer(
    EGLDisplay display,
    AHardwareBuffer* buffer
);