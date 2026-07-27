cmake_minimum_required(VERSION 3.22.1)
project(dreamplayer LANGUAGES C CXX)

find_package(OpenXR REQUIRED CONFIG)

add_library(dreamplayer SHARED native_player.cpp)
target_compile_definitions(dreamplayer PRIVATE
    XR_USE_PLATFORM_ANDROID
    XR_USE_GRAPHICS_API_OPENGL_ES
)
target_link_libraries(dreamplayer PRIVATE
    OpenXR::openxr_loader
    android
    log
    EGL
    GLESv3
    jnigraphics
)
