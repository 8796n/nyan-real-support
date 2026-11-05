#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
# Copyright (C) 2026 8796n <info@8796.jp>
import importlib.util
from pathlib import Path, PurePosixPath
import posixpath
import re
import runpy
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location('loader_source', Path(__file__).with_name('source-loader.py'))
source = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(source)


class SourceTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.files = source.source_files(source.ROOT)
        for name, data in self.files.items():
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)

    def test_export_is_self_contained_and_covers_the_wasm_receipt(self):
        self.assertEqual(source.source_files(self.root), self.files)
        stage = runpy.run_path(str(self.root / 'tools/remote-ui/stage-loader.py'))
        self.assertLessEqual(set(stage['wasm_inputs']()), self.files.keys())
        self.assertEqual(stage['wasm_inputs'](), runpy.run_path(str(source.ROOT / 'tools/remote-ui/stage-loader.py'))['wasm_inputs']())
        result = subprocess.run([sys.executable, str(self.root / 'tools/remote-ui/source-loader.py'), '--check'],
                                capture_output=True, text=True, timeout=10)
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_only_the_allowlist_and_mit_license_are_exported(self):
        (self.root / 'LICENSE').write_text('retired plugin GPL fixture')
        for name in ('secret.pem', 'local.properties', 'src/app/private.cpp',
                     'data/remote-loader/unreviewed.mjs', 'data/remote.html'):
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text('must not be published')
        files = source.source_files(self.root)
        self.assertEqual(files, self.files)
        self.assertTrue(files['LICENSE'].startswith(b'MIT License\n'))
        self.assertNotIn('src/app', '\n'.join(files))
        self.assertNotIn('data/remote.html', files)

    def test_relative_javascript_imports_have_sources_or_the_generated_wasm_glue(self):
        generated = {'data/remote-loader/pairing-core.mjs', 'tools/remote-pairing-wasm/pairing-core.mjs'}
        for name, data in self.files.items():
            if not name.endswith(('.mjs', '.js', '.html')):
                continue
            for imported in re.findall(rb'''(?:\bfrom\s*|\bimport\s*\()\s*['"](\.[^'"]+)['"]''', data):
                target = posixpath.normpath(str(PurePosixPath(name).parent / imported.decode('utf-8')))
                self.assertIn(target, self.files.keys() | generated, (name, target))

    def test_missing_source_and_private_key_replacement_are_rejected(self):
        path = self.root / 'tools/remote-pairing-wasm/entry.cpp'
        path.unlink()
        with self.assertRaisesRegex(ValueError, 'Missing'):
            source.source_files(self.root)
        path.write_bytes(self.files['tools/remote-pairing-wasm/entry.cpp'])
        (self.root / 'data/remote-loader/product-key.pem').write_text('-----BEGIN PRIVATE KEY-----\nfixture\n')
        with self.assertRaisesRegex(ValueError, 'public verification key'):
            source.source_files(self.root)

    def test_new_runtime_asset_or_core_input_requires_allowlist_review(self):
        path = self.root / 'tools/remote-ui/stage-loader.py'
        path.write_bytes(path.read_bytes().replace(b"'entry-capture.js',", b"'entry-capture.js', 'new.mjs',", 1))
        with self.assertRaisesRegex(ValueError, 'runtime source list changed'):
            source.source_files(self.root)
        path.write_bytes(self.files['tools/remote-ui/stage-loader.py'])
        (self.root / 'src/core/remote_pairing/new.cpp').write_text('// fixture\n')
        with self.assertRaisesRegex(ValueError, 'core inputs changed'):
            source.source_files(self.root)

    def test_new_local_header_dependency_is_rejected(self):
        path = self.root / 'src/core/remote_pairing/pairing.cpp'
        path.write_bytes(path.read_bytes() + b'\n#include "unpublished.h"\n')
        with self.assertRaisesRegex(ValueError, r'Missing public C\+\+ header'):
            source.source_files(self.root)

    def test_windows_line_endings_have_identical_public_bytes(self):
        for name in ('scripts/windows-toolchain.ps1', 'licenses/remote-pairing-wasm.txt'):
            (self.root / name).write_bytes(self.files[name].replace(b'\n', b'\r\n'))
        self.assertEqual(source.source_files(self.root), self.files)

    def test_export_preserves_png_bytes_in_the_repository_and_on_checkout(self):
        for name in ('icon-192.png', 'icon-512.png'):
            relative = 'data/remote-loader/' + name
            self.assertEqual(self.files[relative], (source.ROOT / relative).read_bytes())
            self.assertTrue(self.files[relative].startswith(b'\x89PNG\r\n\x1a\n'))
        self.assertIn(b'*.png binary', self.files['.gitattributes'].splitlines())

    def test_redirected_file_is_rejected(self):
        path = (self.root / 'tools/remote-pairing-wasm/entry.cpp').resolve()
        # Creating a real symlink requires extra privileges on some Windows hosts.
        with patch.object(type(path), 'is_symlink', lambda current: current == path), self.assertRaisesRegex(ValueError, 'Redirected'):
            source.source_files(self.root)


if __name__ == '__main__':
    unittest.main()
