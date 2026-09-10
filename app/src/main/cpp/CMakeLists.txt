cmake_minimum_required(VERSION 3.22.1)
project(patchcanvas LANGUAGES CXX)

# Oboe arrives as a prefab package inside its AAR, so there is no source checkout and
# no submodule to keep in step -- the Gradle dependency version is the only version.
find_package(oboe REQUIRED CONFIG)

add_library(patchcanvas SHARED
        audio_engine.cpp
        native_bridge.cpp)

target_compile_features(patchcanvas PRIVATE cxx_std_17)
target_compile_options(patchcanvas PRIVATE -Wall -Wextra -ffast-math)
target_link_libraries(patchcanvas PRIVATE oboe::oboe android log)
