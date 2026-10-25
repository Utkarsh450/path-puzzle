cmake_minimum_required(VERSION 3.22.1)
project(arrowcore LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(arrowcore SHARED puzzle_core.cpp jni_bridge.cpp)
target_compile_options(arrowcore PRIVATE -Wall -Wextra -O2)
