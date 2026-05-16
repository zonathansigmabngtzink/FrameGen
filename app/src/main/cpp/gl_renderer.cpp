#include "gl_renderer.h"
#include "egl_import.h" // <--- Tambahkan ini

// Tambahkan Vertex Shader (vs) yang sebelumnya hilang
static const char* vs = R"(
#version 300 es
layout(location = 0) in vec2 aPosition;
layout(location = 1) in vec2 aTexCoord;

out vec2 vUv;

void main() {
    gl_Position = vec4(aPosition, 0.0, 1.0);
    vUv = aTexCoord;
}
)";

static const char* fs = R"(
#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require

precision highp float;

in vec2 vUv;

uniform samplerExternalOES tex1;
uniform samplerExternalOES tex2;

uniform float blendFactor;

out vec4 fragColor;

void main() {
    vec4 a = texture(tex1, vUv);
    vec4 b = texture(tex2, vUv);
    fragColor = mix(a, b, blendFactor);
}
)";

bool GLRenderer::init(ANativeWindow* window) {

    display = eglGetDisplay(EGL_DEFAULT_DISPLAY);

    eglInitialize(display, 0, 0);

    EGLint attribs[] = {
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
        EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
        EGL_BLUE_SIZE, 8,
        EGL_GREEN_SIZE, 8,
        EGL_RED_SIZE, 8,
        EGL_NONE
    };

    EGLConfig config;
    EGLint numConfigs;

    eglChooseConfig(
        display,
        attribs,
        &config,
        1,
        &numConfigs
    );

    surface = eglCreateWindowSurface(
        display,
        config,
        window,
        nullptr
    );

    EGLint ctxAttribs[] = {
        EGL_CONTEXT_CLIENT_VERSION, 3,
        EGL_NONE
    };

    context = eglCreateContext(
        display,
        config,
        EGL_NO_CONTEXT,
        ctxAttribs
    );

    eglMakeCurrent(
        display,
        surface,
        surface,
        context
    );

    GLuint vsShader = glCreateShader(GL_VERTEX_SHADER);
    glShaderSource(vsShader, 1, &vs, nullptr);
    glCompileShader(vsShader);

    GLuint fsShader = glCreateShader(GL_FRAGMENT_SHADER);
    glShaderSource(fsShader, 1, &fs, nullptr);
    glCompileShader(fsShader);

    program = glCreateProgram();

    glAttachShader(program, vsShader);
    glAttachShader(program, fsShader);

    glLinkProgram(program);

    glDeleteShader(vsShader);
    glDeleteShader(fsShader);

    float vertices[] = {
        -1.0f,-1.0f, 0.0f,1.0f,
         1.0f,-1.0f, 1.0f,1.0f,
        -1.0f, 1.0f, 0.0f,0.0f,

        -1.0f, 1.0f, 0.0f,0.0f,
         1.0f,-1.0f, 1.0f,1.0f,
         1.0f, 1.0f, 1.0f,0.0f
    };

    glGenVertexArrays(1, &vao);
    glGenBuffers(1, &vbo);

    glBindVertexArray(vao);

    glBindBuffer(GL_ARRAY_BUFFER, vbo);
    glBufferData(
        GL_ARRAY_BUFFER,
        sizeof(vertices),
        vertices,
        GL_STATIC_DRAW
    );

    glEnableVertexAttribArray(0);

    glVertexAttribPointer(
        0,
        2,
        GL_FLOAT,
        GL_FALSE,
        4 * sizeof(float),
        (void*)0
    );

    glEnableVertexAttribArray(1);

    glVertexAttribPointer(
        1,
        2,
        GL_FLOAT,
        GL_FALSE,
        4 * sizeof(float),
        (void*)(2 * sizeof(float))
    );

    glGenTextures(1, &externalTexA);
    glGenTextures(1, &externalTexB);

    return true;
}

void GLRenderer::updateTexture(
    AHardwareBuffer* buffer,
    int width,
    int height
) {

    EGLImageKHR image =
        createEGLImageFromBuffer(
            display,
            buffer
        );
    
    std::swap(externalTexA, externalTexB);
    glBindTexture(
        GL_TEXTURE_EXTERNAL_OES,
        externalTexB
    );

    glEGLImageTargetTexture2DOES(
        GL_TEXTURE_EXTERNAL_OES,
        image
    );
}

void GLRenderer::render(float blendFactor) {

    glViewport(0, 0, 1920, 1080);

    glClear(GL_COLOR_BUFFER_BIT);

    glUseProgram(program);

    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_EXTERNAL_OES, externalTexA);

    glActiveTexture(GL_TEXTURE1);
    glBindTexture(GL_TEXTURE_EXTERNAL_OES, externalTexB);

    glUniform1i(
        glGetUniformLocation(program, "tex1"),
        0
    );

    glUniform1i(
        glGetUniformLocation(program, "tex2"),
        1
    );

    glUniform1f(
        glGetUniformLocation(program, "blendFactor"),
        blendFactor
    );

    glBindVertexArray(vao);

    glDrawArrays(GL_TRIANGLES, 0, 6);

    eglSwapBuffers(display, surface);
}

void GLRenderer::destroy() {

    if (vao) {
        glDeleteVertexArrays(1, &vao);
        vao = 0;
    }

    if (vbo) {
        glDeleteBuffers(1, &vbo);
        vbo = 0;
    }

    if (program) {
        glDeleteProgram(program);
        program = 0;
    }

    if (context != EGL_NO_CONTEXT) {
        eglDestroyContext(display, context);
        context = EGL_NO_CONTEXT;
    }

    if (surface != EGL_NO_SURFACE) {
        eglDestroySurface(display, surface);
        surface = EGL_NO_SURFACE;
    }

    if (display != EGL_NO_DISPLAY) {
        eglTerminate(display);
        display = EGL_NO_DISPLAY;
    }
}