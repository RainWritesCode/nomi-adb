cmake_minimum_required(VERSION 3.22.1)
project(nomiadb_tests LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_subdirectory("${CMAKE_CURRENT_SOURCE_DIR}/../../main/cpp" "${CMAKE_BINARY_DIR}/main")

add_executable(spake2_test spake2_test.cpp)
target_link_libraries(spake2_test PRIVATE adb_spake2)

enable_testing()
add_test(NAME spake2 COMMAND spake2_test)
