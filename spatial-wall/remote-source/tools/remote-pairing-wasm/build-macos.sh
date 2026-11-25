#!/bin/sh
# SPDX-License-Identifier: MIT
# Copyright (C) 2026 8796n <info@8796.jp>
# The macOS counterpart of build.ps1: the same checks, native fixture, Wasm build,
# native/Wasm cross-test and build receipt, into build/remote-loader. The pinned SDK
# comes from setup-emsdk-macos.py; EM_CONFIG is set for this script only.
set -eu

repo=$(CDPATH='' cd -- "$(dirname -- "$0")/../.." && pwd)
here="$repo/tools/remote-pairing-wasm"
output="$repo/build/remote-loader"
native="$output/pairing-native"
wasm="$output/pairing-build"
emsdk="$repo/deps/emsdk-spake2-probe"

. "$repo/scripts/macos-build-env.sh"
nyan_require_python
nyan_require_node
# Resolve cmake as scripts/build-macos.sh does: PATH first, then the copy setup-macos.sh keeps.
if command -v cmake >/dev/null 2>&1; then
	cmake_bin=$(command -v cmake)
else
	cmake_bin=$(ls "$repo"/deps/macos-tools/cmake-*/CMake.app/Contents/bin/cmake 2>/dev/null | sort | tail -1 || true)
	[ -n "$cmake_bin" ] || { echo "cmake not found. Run ./scripts/setup-macos.sh first." >&2; exit 1; }
fi
jobs=$(sysctl -n hw.ncpu)

mkdir -p "$output"
python3 "$repo/scripts/setup-remote-pairing.py" --check || { echo "Run scripts/setup-remote-pairing.py first." >&2; exit 1; }
python3 "$here/setup-emsdk-macos.py" --check || { echo "Run tools/remote-pairing-wasm/setup-emsdk-macos.py first." >&2; exit 1; }
python3 "$here/licenses.py"
python3 "$repo/tools/remote-ui/stage-loader.py" --record-wasm start --wasm-dir "$output"

if ! { "$cmake_bin" -S "$here" -B "$native" -DCMAKE_BUILD_TYPE=Release -DCMAKE_C_COMPILER=clang -DCMAKE_CXX_COMPILER=clang++ &&
	"$cmake_bin" --build "$native" --target pairing_pipe --parallel "$jobs"; } > "$output/pairing-native-build.log" 2>&1; then
	tail -40 "$output/pairing-native-build.log" >&2
	echo "Native pairing fixture build failed." >&2
	exit 1
fi
echo "Native pairing fixture built."

compiler="$emsdk/upstream/emscripten/em++"
config="$output/.emscripten-pairing"
cat > "$config" <<EOF
LLVM_ROOT = '$emsdk/upstream/bin'
BINARYEN_ROOT = '$emsdk/upstream'
EMSCRIPTEN_ROOT = '$emsdk/upstream/emscripten'
NODE_JS = '$(command -v node)'
CACHE = '$emsdk/upstream/emscripten/cache'
EOF
export EM_CONFIG="$config"
"$compiler" --version | head -1 | grep -q '6\.0\.13' || { echo "Emscripten 6.0.13 is required." >&2; exit 1; }
"$cmake_bin" -S "$here" -B "$wasm" -DCMAKE_BUILD_TYPE=Release \
	"-DCMAKE_TOOLCHAIN_FILE=$emsdk/upstream/emscripten/cmake/Modules/Platform/Emscripten.cmake"
if ! "$cmake_bin" --build "$wasm" --target pairing_core --parallel "$jobs" > "$output/pairing-wasm-build.log" 2>&1; then
	tail -40 "$output/pairing-wasm-build.log" >&2
	echo "Wasm pairing build failed." >&2
	exit 1
fi
python3 "$here/licenses.py" --map "$wasm/pairing-core.map" --output "$output"
cp "$wasm/pairing-core.mjs" "$wasm/pairing-core.wasm" "$repo/data/remote-loader/pairing.mjs" \
	"$here/browser.html" "$here/browser-checks.mjs" "$output/"
node "$here/test.mjs" "$output"
python3 "$here/test-licenses.py"
python3 "$repo/scripts/setup-remote-pairing.py" --check || { echo "BoringSSL source changed during the build." >&2; exit 1; }
python3 "$repo/tools/remote-ui/stage-loader.py" --record-wasm finish --wasm-dir "$output"
ls -l "$output/pairing-core.mjs" "$output/pairing-core.wasm"
