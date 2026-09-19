cmake_minimum_required(VERSION 3.22.1)
project(patchcanvas LANGUAGES CXX)

# Oboe arrives as a prefab package inside its AAR, so there is no source checkout and
# no submodule to keep in step -- the Gradle dependency version is the only version.
find_package(oboe REQUIRED CONFIG)

# A vendored subset of DaisySP rather than a submodule: DaisySP's own repository carries
# DaisySP-LGPL as a submodule, so `git clone --recursive` here would quietly pull the
# LGPL half we deliberately exclude. See vendor/daisysp/README.md.
#
# Built as its own target, and its headers included as SYSTEM, so upstream is held to
# upstream's warning settings rather than ours. Patching vendored source to satisfy our
# -Werror would mean carrying a diff forever for no benefit.
add_library(daisysp STATIC
        vendor/daisysp/oscillator.cpp
        vendor/daisysp/svf.cpp
        vendor/daisysp/adsr.cpp
        vendor/daisysp/limiter.cpp
        vendor/daisysp/dcblock.cpp
        vendor/daisysp/KarplusString.cpp
        vendor/daisysp/crossfade.cpp)
target_include_directories(daisysp PUBLIC vendor/daisysp)
target_compile_options(daisysp PRIVATE -w)

add_library(patchcanvas SHARED
        audio_engine.cpp
        graph.cpp
        nodes.cpp
        native_bridge.cpp)

target_include_directories(patchcanvas SYSTEM PRIVATE vendor/daisysp)

target_compile_features(patchcanvas PRIVATE cxx_std_17)
target_compile_options(patchcanvas PRIVATE -Wall -Wextra -ffast-math)
target_link_libraries(patchcanvas PRIVATE daisysp oboe::oboe android log)
