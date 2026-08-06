# Builds libdrumengine.so: the Oboe-backed drum playback engine (PRD §6.2.2).
cmake_minimum_required(VERSION 3.22.1)
project(drumengine LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Oboe ships as a prefab AAR; AGP's prefab integration exposes its headers
# and shared library via find_package().
find_package(oboe REQUIRED CONFIG)

add_library(drumengine SHARED drumengine.cpp)

# Let the preprocessor see oboe/Oboe.h.
target_link_libraries(drumengine
        oboe::oboe
        log
        android
)

# Strip unused Oboe code on release builds to keep the .so small.
target_compile_options(drumengine PRIVATE -Wall -Wno-unused-parameter)
