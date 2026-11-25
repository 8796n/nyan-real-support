# SPDX-License-Identifier: MIT
# Copyright (C) 2026 8796n <info@8796.jp>
# Sourced by the macOS entry scripts (setup, build, package, notarize) with
# $repo set: puts the build's Python and Node.js on PATH, so the child scripts
# and CMake all pick up the same ones. A tool already on PATH that can do the
# job wins; otherwise the copy scripts/setup-macos.sh keeps under
# deps/macos-tools/ goes first. Nothing is installed here.

# tomllib (transport licence audit) and hashlib.file_digest (Android RDP fetch)
# both arrived in Python 3.11; the Command Line Tools still ship 3.9.
nyan_python_ok() {
	"$1" -c 'import tomllib, hashlib; hashlib.file_digest' >/dev/null 2>&1
}

# What tools/test-remote-load.js needs. CMake registers that test (remote-page)
# only when it finds node, so a Mac without one silently ran one test fewer.
nyan_node_ok() {
	"$1" -e 'require("node:assert/strict"); require("node:vm")' >/dev/null 2>&1
}

# nyan_use_tool NAME CHECK DIR...: keep PATH when NAME on it passes CHECK,
# otherwise prepend the last DIR whose NAME passes (globs sort by name).
nyan_use_tool() {
	nyan_tool=$1
	nyan_check=$2
	shift 2
	if command -v "$nyan_tool" >/dev/null 2>&1 && "$nyan_check" "$nyan_tool"; then
		return 0
	fi
	nyan_found=""
	for nyan_dir in "$@"; do
		if [ -x "$nyan_dir/$nyan_tool" ] && "$nyan_check" "$nyan_dir/$nyan_tool"; then
			nyan_found=$nyan_dir
		fi
	done
	[ -n "$nyan_found" ] || return 1
	PATH="$nyan_found:$PATH"
}

# The directory names are the ones uv and the official Node.js tarball use, so
# copies staged by hand in the same place are found too.
nyan_use_python() {
	nyan_use_tool python3 nyan_python_ok "$repo"/deps/macos-tools/python/cpython-*/bin
}

nyan_use_node() {
	nyan_use_tool node nyan_node_ok "$repo"/deps/macos-tools/node-v*-darwin-*/bin
}

nyan_require_python() {
	nyan_use_python || {
		echo "Python 3.11+ not found. Run ./scripts/setup-macos.sh first." >&2
		return 1
	}
}

nyan_require_node() {
	nyan_use_node || {
		echo "Node.js not found. Run ./scripts/setup-macos.sh first." >&2
		return 1
	}
}
