#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
# Copyright (C) 2026 8796n <info@8796.jp>
"""Verify the pinned Wasm runtime notices and copy complete notices beside the output."""
import argparse
import hashlib
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "deps/emsdk-spake2-probe/upstream/emscripten"
SPEC = Path(__file__).with_name("runtime.json")
NOTICE = ROOT / "licenses/remote-pairing-wasm.txt"


def digest(data):
    return hashlib.sha256(data).hexdigest()


def render(spec):
    if (SOURCE / "emscripten-version.txt").read_text().strip().strip('"') != spec["version"]:
        raise ValueError("Emscripten version differs from reviewed runtime")
    text = (f"Remote pairing WebAssembly runtime notices\nEmscripten {spec['version']}\n"
            f"Source: https://github.com/emscripten-core/emscripten/tree/{spec['revision']}\n"
            "Emscripten and emmalloc: MIT selected; full upstream alternatives retained.\n"
            "musl: MIT and the included original attribution notices.\n"
            "LLVM runtimes: Apache-2.0 WITH LLVM-exception; original legacy terms retained.\n"
            "BoringSSL notices are supplied alongside this document.\n\n"
            "===== nyan Real pairing core and heap adapter (MIT) =====\n"
            "Copyright (C) 2026 8796n <info@8796.jp>\n\n"
            "Permission is hereby granted, free of charge, to any person obtaining a copy\n"
            "of this software and associated documentation files (the \"Software\"), to deal\n"
            "in the Software without restriction, including without limitation the rights\n"
            "to use, copy, modify, merge, publish, distribute, sublicense, and/or sell\n"
            "copies of the Software, and to permit persons to whom the Software is\n"
            "furnished to do so, subject to the following conditions:\n\n"
            "The above copyright notice and this permission notice shall be included in all\n"
            "copies or substantial portions of the Software.\n\n"
            "THE SOFTWARE IS PROVIDED \"AS IS\", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR\n"
            "IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,\n"
            "FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE\n"
            "AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER\n"
            "LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,\n"
            "OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE\n"
            "SOFTWARE.\n")
    for entry in spec["files"]:
        path = SOURCE / entry["path"]
        data = path.read_bytes()
        if digest(data) != entry["sha256"]:
            raise ValueError(f"Runtime notice source changed: {entry['path']}")
        original = data.decode("utf-8")
        if "notice_lines" in entry:
            original = "".join(original.splitlines(keepends=True)[:entry["notice_lines"]])
        text += f"\n===== {entry['path']} =====\n" + original + "\n"
    return text.encode("utf-8")


def check_link_map(path, spec):
    libraries = {}
    owned = set()
    for archive, member in re.findall(r"([^\s]+\.a)\(([^)]*)\)", path.read_text(encoding="utf-8")):
        archive = archive.replace("\\", "/")
        if "/cache/sysroot/lib/wasm32-emscripten/" in archive:
            libraries.setdefault(archive.rsplit("/", 1)[1], set()).add(member)
        else:
            owned.add(archive.rsplit("/", 1)[1])
    libraries = {name: sorted(members) for name, members in sorted(libraries.items())}
    if libraries != spec["runtime_archive_members"] or owned != {"libnyan_remote_pairing.a", "libcrypto.a"}:
        raise ValueError("Linked runtime objects changed; review their notices before distribution")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--write", action="store_true", help="Regenerate the tracked runtime notice after review")
    parser.add_argument("--output", type=Path, help="Copy both checked runtime and BoringSSL notices beside Wasm")
    parser.add_argument("--map", type=Path, help="Check the actual linked runtime object set")
    args = parser.parse_args()
    spec = json.loads(SPEC.read_text(encoding="utf-8"))
    expected = render(spec)
    if args.write:
        NOTICE.write_bytes(expected)
    if NOTICE.read_bytes() != expected:
        raise ValueError("Bundled Wasm runtime notices differ from reviewed originals")
    boring = (ROOT / "licenses/boringssl.txt").read_bytes()
    if digest(boring) != spec["boringssl_notice_sha256"]:
        raise ValueError("BoringSSL notice changed; review the Wasm bundle")
    if args.map:
        check_link_map(args.map, spec)
    if args.output:
        if not args.map:
            raise ValueError("Distributable notices require the checked link map")
        args.output.mkdir(parents=True, exist_ok=True)
        (args.output / "remote-pairing-wasm.txt").write_bytes(expected)
        (args.output / "boringssl.txt").write_bytes(boring)
    print(f"Verified {len(spec['files'])} runtime notice sources and the BoringSSL notice")


if __name__ == "__main__":
    main()
