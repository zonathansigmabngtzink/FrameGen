#include "gl_renderer.h"

#include <jni.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <android/hardware_buffer_jni.h>

#include <thread>
#include <chrono>
#include <atomic>
#include <mutex>
#include <condition_variable>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "FrameGen", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "FrameGen", __VA_ARGS__)

// ======================================================
// GLOBAL
// ======================================================

static std::atomic<bool> running(false);

static ANativeWindow* window = nullptr;

static GLRenderer renderer;

static std::thread renderThread;

static std::mutex renderMtx;
static std::condition_variable renderCv;

static std::atomic<bool> frameReady(false);

static std::mutex textureMutex;

// timing EMA
static long long lastFrameTime = 0;
static long long emaIntervalNs = 16666666LL;

// ukuran frame
static int gWidth = 0;
static int gHeight = 0;

// ======================================================
// RENDER LOOP
// ======================================================

static void renderLoop() {

    LOGI("Render thread started");

    while (running.load()) {

        std::unique_lock<std::mutex> lock(renderMtx);

        renderCv.wait(lock, [] {
            return frameReady.load() || !running.load();
        });

        if (!running.load()) {
            break;
        }

        frameReady = false;

        lock.unlock();

        // =========================================
        // DRAW WITH OPENGL ES
        // =========================================

        {
            std::lock_guard<std::mutex> texLock(textureMutex);

            static float blend = 0.0f;

            blend += 0.08f;

            if (blend > 1.0f)
                blend = 0.0f;

            renderer.render(blend);
        }

        // =========================================
        // FRAME PACING
        // =========================================

        long long visibleNs =
            emaIntervalNs / 2;

        visibleNs =
            std::max(
                2000000LL,
                std::min(
                    visibleNs,
                    12000000LL
                )
            );

        std::this_thread::sleep_for(
            std::chrono::nanoseconds(
                visibleNs
            )
        );
    }

    LOGI("Render thread stopped");
}

// ======================================================
// JNI SURFACE
// ======================================================

extern "C"
JNIEXPORT void JNICALL
Java_com_framegen_app_NativeBridge_setSurface(
    JNIEnv* env,
    jclass clazz,
    jobject surface
) {

    // release lama
    if (window) {

        renderer.destroy();

        ANativeWindow_release(window);

        window = nullptr;
    }

    if (!surface) {
        LOGI("Surface detached");
        return;
    }

    // attach baru
    window =
        ANativeWindow_fromSurface(
            env,
            surface
        );

    if (!window) {

        LOGE("Failed create ANativeWindow");

        return;
    }

    LOGI("Surface attached");

    // init EGL + GLES
    if (!renderer.init(window)) {

        LOGE("Renderer init failed");

        ANativeWindow_release(window);

        window = nullptr;

        return;
    }

    LOGI("Renderer initialized");
}

// ======================================================
// JNI PUSH HARDWARE BUFFER
// ======================================================

extern "C"
JNIEXPORT void JNICALL
Java_com_framegen_app_NativeBridge_pushHardwareBuffer(
    JNIEnv* env,
    jclass clazz,
    jobject hardwareBuffer,
    jint width,
    jint height
) {

    if (!running.load()) {
        return;
    }

    if (!hardwareBuffer) {
        return;
    }

    AHardwareBuffer* ahb =
        AHardwareBuffer_fromHardwareBuffer(
            env,
            hardwareBuffer
        );

    if (!ahb) {

        LOGE("AHardwareBuffer null");

        return;
    }

    // =========================================
    // EMA TIMING
    // =========================================

    auto now =
        std::chrono::steady_clock::now()
            .time_since_epoch();

    long long nowNs =
        std::chrono::duration_cast
        <
            std::chrono::nanoseconds
        >(now).count();

    if (lastFrameTime > 0) {

        long long diff =
            nowNs - lastFrameTime;

        diff =
            std::max(
                8000000LL,
                std::min(
                    diff,
                    50000000LL
                )
            );

        emaIntervalNs =
            (
                emaIntervalNs * 8 +
                diff * 2
            ) / 10;
    }

    lastFrameTime = nowNs;

    // =========================================
    // SAVE SIZE
    // =========================================

    gWidth = width;
    gHeight = height;

    // =========================================
    // GPU IMPORT
    // AHardwareBuffer
    // -> EGLImageKHR
    // -> GL_TEXTURE_EXTERNAL_OES
    // =========================================

    {
        std::lock_guard<std::mutex> lock(textureMutex);

        renderer.updateTexture(
            ahb,
            width,
            height
        );
    }

    // =========================================
    // NOTIFY RENDER THREAD
    // =========================================

    {
        std::lock_guard<std::mutex> lock(renderMtx);

        frameReady = true;
    }

    renderCv.notify_one();
}

// ======================================================
// JNI START
// ======================================================

extern "C"
JNIEXPORT void JNICALL
Java_com_framegen_app_NativeBridge_startFrameGen(
    JNIEnv* env,
    jclass clazz
) {

    if (running.load()) {
        return;
    }

    running = true;

    frameReady = false;

    lastFrameTime = 0;

    renderThread =
        std::thread(renderLoop);

    LOGI("FrameGen STARTED");
}

// ======================================================
// JNI STOP
// ======================================================

extern "C"
JNIEXPORT void JNICALL
Java_com_framegen_app_NativeBridge_stopFrameGen(
    JNIEnv* env,
    jclass clazz
) {

    if (!running.load()) {
        return;
    }

    running = false;

    renderCv.notify_all();

    if (renderThread.joinable()) {

        renderThread.join();
    }

    renderer.destroy();

    if (window) {

        ANativeWindow_release(window);

        window = nullptr;
    }

    gWidth = 0;
    gHeight = 0;

    lastFrameTime = 0;

    LOGI("FrameGen STOPPED");
}