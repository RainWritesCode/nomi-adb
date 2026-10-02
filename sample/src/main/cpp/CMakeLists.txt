cmake_minimum_required(VERSION 3.22.1)
project(sample LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_executable(libechod.so echod.cpp)
target_compile_options(libechod.so PRIVATE -Wall -Wextra -fPIE)
target_link_options(libechod.so PRIVATE -pie -Wl,-z,max-page-size=16384)
