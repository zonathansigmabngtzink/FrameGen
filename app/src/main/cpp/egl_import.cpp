#define EGL_EGLEXT_PROTOTYPES
#include "egl_import.h"

EGLImageKHR createEGLImageFromBuffer(
    EGLDisplay display,
    AHardwareBuffer* buffer
) {
    EGLClientBuffer clientBuffer = eglGetNativeClientBufferANDROID(buffer);

    EGLint attrs[] = {
        EGL_IMAGE_PRESERVED_KHR, EGL_TRUE,
        EGL_NONE
    };

    return eglCreateImageKHR(
        display,
        EGL_NO_CONTEXT,
        EGL_NATIVE_BUFFER_ANDROID,
        clientBuffer,
        attrs
    );
}