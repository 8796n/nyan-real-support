# SPDX-License-Identifier: MIT
cmake_minimum_required(VERSION 3.22)
project(nyan_remote_pairing_wasm LANGUAGES C CXX)
get_filename_component(NYAN_REPO "${CMAKE_CURRENT_LIST_DIR}/../.." ABSOLUTE)
add_subdirectory("${NYAN_REPO}/src/core/remote_pairing" remote_pairing)
if(EMSCRIPTEN)
  add_executable(pairing_core entry.cpp)
  target_link_libraries(pairing_core PRIVATE nyan_remote_pairing)
  set_target_properties(pairing_core PROPERTIES OUTPUT_NAME pairing-core SUFFIX .mjs)
  target_link_options(pairing_core PRIVATE
    -O2 --no-entry
    -sMODULARIZE=1 -sEXPORT_ES6=1 -sENVIRONMENT=web,worker,node -sMIN_NODE_VERSION=240000
    -sFILESYSTEM=0 -sMALLOC=emmalloc -sABORTING_MALLOC=0
    -sALLOW_MEMORY_GROWTH=0 -sINITIAL_MEMORY=16777216 -sSTACK_SIZE=1048576
    "-sEXPORTED_FUNCTIONS=_malloc,_free,_nyan_pairing_new,_nyan_pairing_message,_nyan_pairing_process_peer,_nyan_pairing_confirmation,_nyan_pairing_verify_confirmation,_nyan_pairing_enrollment_proof,_nyan_pairing_free"
    -sEXPORTED_RUNTIME_METHODS=HEAPU8
    "-Wl,-Map=${CMAKE_CURRENT_BINARY_DIR}/pairing-core.map")
else()
  add_executable(pairing_pipe native.cpp)
  target_compile_features(pairing_pipe PRIVATE cxx_std_17)
  target_link_libraries(pairing_pipe PRIVATE nyan_remote_pairing)
endif()
