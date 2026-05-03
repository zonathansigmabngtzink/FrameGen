#include <jni.h>
#include <android/log.h>
#include <thread>
#include <chrono>
#include <atomic>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "FrameGen", __VA_ARGS__)

static std::atomic<bool> running(false);

extern "C"
JNIEXPORT void JNICALL
Java_com_framegen_app_NativeBridge_startFrameGen(
        JNIEnv* env,
        jobject thiz
) {
    if (running) return; // 🔥 cegah double start

    LOGI("Frame Generation Started");

    running = true;

    int realFPS = 60;
    int frameTime = 1000 / realFPS;
    int fakeFrameTime = frameTime / 2;

    while (running.load()) {

        int frameA = 100;
        int frameB = 200;

        int middleFrame = (frameA + frameB) / 2;

        LOGI("Frame A");
        std::this_thread::sleep_for(std::chrono::milliseconds(fakeFrameTime));

        if (!running.load()) break;

        LOGI("Generated Middle Frame: %d", middleFrame);
        std::this_thread::sleep_for(std::chrono::milliseconds(fakeFrameTime));

        if (!running.load()) break;

        LOGI("Frame B");
        std::this_thread::sleep_for(std::chrono::milliseconds(fakeFrameTime));
    }

    running = false;
    LOGI("Frame Generation Stopped");
}

extern "C"
JNIEXPORT void JNICALL
Java_com_framegen_app_NativeBridge_stopFrameGen(
        JNIEnv* env,
        jobject thiz
) {
    running = false;
}