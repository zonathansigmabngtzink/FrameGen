cmake_minimum_required(VERSION 3.18.1)

project("framegen")

add_library(
    framegen
    SHARED
    framegen.cpp
    gl_renderer.cpp
    egl_import.cpp
)

find_library(
    log-lib
    log
)

target_link_libraries(
    framegen
    ${log-lib}
    android
)

find_library(
    GLESv3-lib
    GLESv3
)

find_library(
    EGL-lib
    EGL
)

target_link_libraries(
    framegen
    ${log-lib}
    android
    ${GLESv3-lib}
    GLESv2
    ${EGL-lib}
)