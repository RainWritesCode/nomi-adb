cmake_minimum_required(VERSION 3.22.1)
project(nomiadb LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(adb_spake2 STATIC sha512.cpp spake2.cpp)
target_include_directories(adb_spake2 PUBLIC "${CMAKE_CURRENT_SOURCE_DIR}/include")
target_compile_options(adb_spake2 PRIVATE -Wall -Wextra -fvisibility=hidden)

if(ANDROID)
    add_library(nomiadb SHARED spake2_jni.cpp)
    target_link_libraries(nomiadb PRIVATE adb_spake2)
    target_compile_options(nomiadb PRIVATE -Wall -Wextra -fvisibility=hidden)
    target_link_options(nomiadb PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL -Wl,-z,max-page-size=16384)
endif()
