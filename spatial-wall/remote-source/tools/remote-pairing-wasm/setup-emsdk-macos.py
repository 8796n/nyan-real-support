#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
# Copyright (C) 2026 8796n <info@8796.jp>
"""Install and verify the pinned Emscripten SDK for the remote pairing Wasm on Apple silicon macOS.

The same emsdk commit and Emscripten release as the Windows SDK (tools/boringssl-spake2-probe/WASM.md),
in the same dedicated, ignored deps/emsdk-spake2-probe. The official archive is kept and checked
against runtime.json; nothing global (PATH, ~/.emscripten) is changed.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
SDK = ROOT / "deps/emsdk-spake2-probe"
SPEC = Path(__file__).with_name("runtime.json")
REPOSITORY = "https://github.com/emscripten-core/emsdk.git"


def git(*args, capture=False):
    result = subprocess.run(["git", "-C", str(SDK), *args], check=True, text=True,
                            stdout=subprocess.PIPE if capture else None)
    return result.stdout.strip() if capture else None


def digest(path):
    hasher = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1 << 20), b""):
            hasher.update(block)
    return hasher.hexdigest()


def verify(spec, archive):
    if git("rev-parse", "HEAD", capture=True) != spec["emsdk_revision"]:
        raise ValueError("emsdk revision differs from runtime.json")
    kept = SDK / "downloads" / archive["file"]
    if not kept.is_file():
        raise ValueError(f"The kept SDK archive is missing: {kept}")
    if kept.stat().st_size != archive["size"] or digest(kept) != archive["sha256"]:
        raise ValueError("The kept SDK archive differs from runtime.json")
    emscripten = SDK / "upstream/emscripten"
    if (emscripten / "emscripten-version.txt").read_text().strip().strip('"') != spec["version"]:
        raise ValueError("Emscripten version differs from runtime.json")
    if not (emscripten / "em++").is_file():
        raise ValueError("em++ is missing from the pinned SDK")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="Verify without downloading or changing the SDK")
    args = parser.parse_args()
    if sys.platform != "darwin" or platform.machine() != "arm64":
        raise SystemExit("This helper covers Apple silicon macOS; Windows follows tools/boringssl-spake2-probe/WASM.md")
    spec = json.loads(SPEC.read_text(encoding="utf-8"))
    archive = spec["sdk_archives"]["mac-arm64"]
    if not args.check:
        if not SDK.exists():
            SDK.mkdir(parents=True)
            git("init", "--quiet")
            git("remote", "add", "origin", REPOSITORY)
        if not (SDK / ".git").is_dir():
            raise ValueError(f"Existing path is not the dedicated emsdk checkout: {SDK}")
        current = subprocess.run(["git", "-C", str(SDK), "rev-parse", "--verify", "HEAD"], text=True, capture_output=True)
        if current.returncode or current.stdout.strip() != spec["emsdk_revision"]:
            git("fetch", "--depth", "1", REPOSITORY, spec["emsdk_revision"])
            git("checkout", "--detach", spec["emsdk_revision"])
        # emsdk deletes the archive after unpacking unless asked to keep it; it is what gets checked.
        environment = dict(os.environ, EMSDK_KEEP_DOWNLOADS="1")
        subprocess.run([sys.executable, str(SDK / "emsdk.py"), "install", f"releases-{spec['release_build']}-64bit"],
                       cwd=SDK, env=environment, check=True)
    verify(spec, archive)
    print(f"Emscripten {spec['version']} ({archive['file']}) verified")


if __name__ == "__main__":
    main()
