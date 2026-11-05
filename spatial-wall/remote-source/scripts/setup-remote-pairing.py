#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
# Copyright (C) 2026 8796n <info@8796.jp>
"""Fetch and verify the unchanged BoringSSL revision used for LAN pairing."""

import argparse
import hashlib
import json
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
SPEC = ROOT / "src/core/remote_pairing/boringssl.json"
SOURCE = ROOT / "deps/boringssl"
NOTICE = ROOT / "licenses/boringssl.txt"


def git(*args, capture=False):
    result = subprocess.run(["git", "-C", str(SOURCE), *args], check=True,
                            text=True, stdout=subprocess.PIPE if capture else None)
    return result.stdout.strip() if capture else None


def notice_text(spec):
    text = (f"BoringSSL (unmodified source)\nRevision: {spec['revision']}\n"
            f"Source: {spec['repository']}/+/{spec['revision']}\n"
            "License: Apache-2.0; additional upstream Go test terms retained below.\n\n")
    for name in ("LICENSE", "AUTHORS", "third_party/fiat/LICENSE", "third_party/fiat/AUTHORS"):
        text += f"\n===== {name} =====\n" + (SOURCE / name).read_text(encoding="utf-8") + "\n"
    return text


def verify(spec):
    if git("rev-parse", "HEAD", capture=True) != spec["revision"]:
        raise ValueError("BoringSSL revision differs; run scripts/setup-remote-pairing.py")
    if git("status", "--porcelain", "--untracked-files=all", capture=True):
        raise ValueError("BoringSSL source has local changes; refusing to build or overwrite it")
    for relative, expected in spec["licenseFiles"].items():
        if hashlib.sha256((SOURCE / relative).read_bytes()).hexdigest() != expected:
            raise ValueError(f"BoringSSL notice checksum differs: {relative}")
    if NOTICE.read_text(encoding="utf-8") != notice_text(spec):
        raise ValueError("Bundled BoringSSL notices differ from the pinned upstream source")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="Verify without downloading or changing the source")
    args = parser.parse_args()
    spec = json.loads(SPEC.read_text(encoding="utf-8"))
    if not args.check:
        if not SOURCE.exists():
            SOURCE.mkdir(parents=True)
            git("init")
            git("config", "core.autocrlf", "false")
            git("remote", "add", "origin", spec["repository"])
        if not (SOURCE / ".git").is_dir():
            raise ValueError(f"Existing path is not the dedicated BoringSSL checkout: {SOURCE}")
        if git("status", "--porcelain", "--untracked-files=all", capture=True):
            raise ValueError("BoringSSL source has local changes; refusing to overwrite it")
        current = subprocess.run(["git", "-C", str(SOURCE), "rev-parse", "--verify", "HEAD"],
                                 text=True, capture_output=True)
        if current.returncode or current.stdout.strip() != spec["revision"]:
            git("fetch", "--depth", "1", spec["repository"], spec["revision"])
            git("-c", "core.autocrlf=false", "checkout", "--detach", spec["revision"])
    verify(spec)
    print(f"BoringSSL {spec['revision']} verified")


if __name__ == "__main__":
    main()
