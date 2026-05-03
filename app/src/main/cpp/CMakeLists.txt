cmake_minimum_required(VERSION 3.18.1)

project("framegen")

add_library(
    framegen
    SHARED
    framegen.cpp
)

find_library(
    log-lib
    log
)

target_link_libraries(
    framegen
    ${log-lib}
)