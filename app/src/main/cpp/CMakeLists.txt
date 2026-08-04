# Time stretching and pitch shifting, on top of Bungee.
#
# Bungee's own CMakeLists is deliberately not used: it requires CMake 3.30 (the
# NDK ships 3.22) and unconditionally builds a command-line tool we have no use
# for on a phone. Its sources are compiled here instead, unmodified, with the
# same include paths and definitions its own build uses.
#
# Bungee is MPL-2.0. It is fetched from upstream rather than copied into this
# repository so the licensed sources stay exactly as published; see the
# fetchBungee task in app/build.gradle.kts.

cmake_minimum_required(VERSION 3.22)
project(spotstretch CXX C)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(BUNGEE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../native-dsp/bungee)

if(NOT EXISTS ${BUNGEE_DIR}/bungee/Bungee.h)
    message(FATAL_ERROR
        "Bungee sources missing at ${BUNGEE_DIR}. The fetchBungee Gradle task "
        "should have cloned them; run ./gradlew :app:fetchBungee.")
endif()

# Bungee's FFT backend, built with the same flags its own CMakeLists uses.
add_library(pffft STATIC
    ${BUNGEE_DIR}/submodules/pffft/pffft.c
    ${BUNGEE_DIR}/submodules/pffft/fftpack.c)
target_compile_options(pffft PRIVATE -ffast-math -fno-finite-math-only -fno-exceptions)

file(GLOB BUNGEE_SOURCES ${BUNGEE_DIR}/src/*.cpp)

add_library(spotstretch SHARED stretch.cpp ${BUNGEE_SOURCES})

target_include_directories(spotstretch PRIVATE
    ${BUNGEE_DIR}
    ${BUNGEE_DIR}/submodules
    ${BUNGEE_DIR}/submodules/eigen)

# eigen_assert is redirected the same way Bungee's own build does it, so Eigen
# failures surface through Bungee's assertion handler instead of calling abort.
target_compile_definitions(spotstretch PRIVATE
    BUNGEE_SELF_TEST=0
    eigen_assert=BUNGEE_ASSERT1
    BUNGEE_VERSION="${BUNGEE_VERSION}")

target_link_libraries(spotstretch PRIVATE pffft log)
