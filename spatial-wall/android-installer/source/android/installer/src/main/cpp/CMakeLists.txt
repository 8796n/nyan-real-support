# SPDX-License-Identifier: MIT
# Copyright (C) 2026 8796n <info@8796.jp>
cmake_minimum_required(VERSION 3.22.1)
project(nyan_android_installer LANGUAGES CXX)
get_filename_component(NYAN_REPO_ROOT "${CMAKE_CURRENT_LIST_DIR}/../../../../.." ABSOLUTE)
add_subdirectory("${NYAN_REPO_ROOT}/src/core/remote_pairing" remote_pairing)
# Reuse the same JNI source and library name without the glasses/RDP/rendering runtime.
add_library(nyan_android_core SHARED "${NYAN_REPO_ROOT}/android/app/src/main/cpp/native_pairing.cpp")
target_compile_features(nyan_android_core PRIVATE cxx_std_20)
target_compile_options(nyan_android_core PRIVATE -Wall -Wextra -Wpedantic)
target_compile_definitions(nyan_android_core PRIVATE BORINGSSL_PREFIX=NYAN_SPAKE2)
target_link_libraries(nyan_android_core PRIVATE nyan_remote_pairing crypto)
