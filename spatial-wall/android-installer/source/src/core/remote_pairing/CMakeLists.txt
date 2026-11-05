# SPDX-License-Identifier: MIT
# Copyright (C) 2026 8796n <info@8796.jp>
get_filename_component(NYAN_PAIRING_REPO "${CMAKE_CURRENT_LIST_DIR}/../../.." ABSOLUTE)
set(NYAN_PAIRING_BORINGSSL "${NYAN_PAIRING_REPO}/deps/boringssl")
if(NOT EXISTS "${NYAN_PAIRING_BORINGSSL}/CMakeLists.txt")
  message(FATAL_ERROR "Run scripts/setup-remote-pairing.py before building remote pairing.")
endif()

# These directory-scoped settings do not alter the parent project's cache.
set(BUILD_TESTING OFF)
set(BUILD_SHARED_LIBS OFF)
set(OPENSSL_NO_ASM ON)
set(BORINGSSL_PREFIX NYAN_SPAKE2)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(CMAKE_C_VISIBILITY_PRESET hidden)
set(CMAKE_CXX_VISIBILITY_PRESET hidden)
set(CMAKE_VISIBILITY_INLINES_HIDDEN ON)
add_subdirectory("${NYAN_PAIRING_BORINGSSL}" boringssl EXCLUDE_FROM_ALL)

add_library(nyan_remote_pairing STATIC pairing.cpp window.cpp devices.cpp snapshot.cpp tickets.cpp registration.cpp)
target_compile_features(nyan_remote_pairing PRIVATE cxx_std_17)
target_compile_definitions(nyan_remote_pairing PRIVATE BORINGSSL_PREFIX=NYAN_SPAKE2)
target_include_directories(nyan_remote_pairing PUBLIC "${CMAKE_CURRENT_LIST_DIR}/..")
target_link_libraries(nyan_remote_pairing PRIVATE crypto)
if(MSVC)
  target_compile_options(nyan_remote_pairing PRIVATE /utf-8 /W4)
else()
  target_compile_options(nyan_remote_pairing PRIVATE -Wall -Wextra -Wpedantic)
endif()
