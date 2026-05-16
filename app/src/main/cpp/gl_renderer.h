#pragma once

// Tambahkan makro ini agar fungsi ekstensi dikenali
#define EGL_EGLEXT_PROTOTYPES
#define GL_GLEXT_PROTOTYPES

#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <android/native_window.h>
#include <vector>
#include <EGL/eglext.h>
#include <GLES2/gl2ext.h>

class GLRenderer {
public:
    GLuint externalTexA = 0;
    GLuint externalTexB = 0;

    EGLImageKHR eglImageA = EGL_NO_IMAGE_KHR;
    EGLImageKHR eglImageB = EGL_NO_IMAGE_KHR;
    
    bool init(ANativeWindow* window);

    void render(float blendFactor);

    void updateTexture(
        AHardwareBuffer* buffer,
        int width,
        int height
    );

    void destroy();
private:
    EGLDisplay display = EGL_NO_DISPLAY;
    EGLSurface surface = EGL_NO_SURFACE;
    EGLContext context = EGL_NO_CONTEXT;

    GLuint program = 0;
    GLuint vao = 0;
    GLuint vbo = 0;

    // HAPUS externalTexA dan B yang ada di sini sebelumnya

    float currentBlend = 0.0f;
};