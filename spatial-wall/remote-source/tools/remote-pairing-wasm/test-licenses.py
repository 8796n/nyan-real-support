#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
# Copyright (C) 2026 8796n <info@8796.jp>
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("notices", Path(__file__).with_name("licenses.py"))
notices = importlib.util.module_from_spec(spec)
spec.loader.exec_module(notices)


class NoticeTests(unittest.TestCase):
    def test_bundled_notice_contains_complete_reviewed_originals(self):
        manifest = json.loads(notices.SPEC.read_text())
        self.assertEqual(notices.render(manifest), notices.NOTICE.read_bytes())
        for marker in (b"MIT", b"LLVM Exceptions", b"Rich Felker", b"Emscripten authors", b"CREDITS.TXT"):
            self.assertIn(marker, notices.NOTICE.read_bytes())

    def test_changed_notice_source_fails_closed(self):
        with tempfile.TemporaryDirectory() as temporary:
            source = Path(temporary)
            (source / "emscripten-version.txt").write_text('"6.0.13"')
            (source / "LICENSE").write_bytes(b"changed")
            manifest = dict(version="6.0.13", revision="fixed",
                            files=[dict(path="LICENSE", sha256=notices.digest(b"original"))])
            with patch.object(notices, "SOURCE", source), self.assertRaisesRegex(ValueError, "source changed"):
                notices.render(manifest)

    def test_new_runtime_or_library_requires_review(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "fixture.map"
            base = ("remote_pairing/libnyan_remote_pairing.a(pairing.cpp.o)\n"
                    "remote_pairing/boringssl/libcrypto.a(curve25519.cc.o)\n"
                    "/sdk/cache/sysroot/lib/wasm32-emscripten/libc.a(getentropy.o)\n")
            manifest = dict(runtime_archive_members={"libc.a": ["getentropy.o"]})
            path.write_text(base)
            notices.check_link_map(path, manifest)
            for extra in ("/sdk/cache/sysroot/lib/wasm32-emscripten/libc.a(unreviewed.o)",
                          "external/libother.a(unreviewed.o)"):
                path.write_text(base + extra)
                with self.assertRaisesRegex(ValueError, "objects changed"):
                    notices.check_link_map(path, manifest)
            path.write_text("")
            with self.assertRaisesRegex(ValueError, "objects changed"):
                notices.check_link_map(path, manifest)


if __name__ == "__main__":
    unittest.main()
