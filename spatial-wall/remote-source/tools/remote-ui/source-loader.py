#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
# Copyright (C) 2026 8796n <info@8796.jp>
"""Return the explicit, rebuildable remote-source/ publication map; never publish it."""
import argparse
import ast
from pathlib import Path, PurePosixPath
import re

ROOT = Path(__file__).resolve().parents[2]
LOADER = (
    'LICENSE', 'index.html', 'entry-capture.js', 'entry.css', 'entry.mjs',
    'connection-data.mjs', 'device-store.mjs', 'pairing-client.mjs', 'pairing.mjs',
    'device-frame.mjs', 'device-connection.mjs', 'ui-frame.mjs', 'frame-bridge.js',
    'frame.js', 'frame.html', 'frame-source.mjs', 'identity.mjs', 'transport.mjs',
    'verify-ui.mjs', 'browser-support.mjs', 'offline.mjs', 'service-worker.js.in', 'product-key.pem',
    'manifest.webmanifest', 'icon-192.png', 'icon-512.png',
)
CORE = (
    'CMakeLists.txt', 'boringssl.json', 'pairing.cpp', 'pairing.h', 'window.cpp', 'window.h',
    'devices.cpp', 'devices.h', 'snapshot.cpp', 'snapshot.h', 'tickets.cpp', 'tickets.h',
    'registration.cpp', 'registration.h',
)
WASM = (
    'build.ps1', 'CMakeLists.txt', 'entry.cpp', 'native.cpp', 'runtime.json', 'licenses.py',
    'test-licenses.py', 'test.mjs', 'pairing.mjs', 'browser.html', 'browser-checks.mjs',
)
FIXED = (
    'scripts/setup-remote-pairing.py', 'scripts/windows-toolchain.ps1',
    'src/core/ws_message_validation.h', 'licenses/boringssl.txt', 'licenses/remote-pairing-wasm.txt',
    'tools/remote-ui/stage-loader.py', 'tools/remote-ui/source-loader.py',
    'tools/remote-ui/test-source-loader.py',
    'tools/remote-ui/source/README.md', 'tools/remote-ui/source/README.ja.md',
)


def check_dependencies(root: Path, files: dict[str, bytes]) -> None:
    # New executable assets or core compilation inputs require an explicit publication review.
    stage = ast.parse(files['tools/remote-ui/stage-loader.py'])
    assets = [ast.literal_eval(node.value) for node in stage.body if isinstance(node, ast.Assign)
              and any(isinstance(target, ast.Name) and target.id in {'SOURCE_ASSETS', 'ICON_ASSETS'} for target in node.targets)]
    if len(assets) != 2 or set().union(*assets, {'index.html', 'manifest.webmanifest', 'service-worker.js.in'}) != set(LOADER):
        raise ValueError('Loader runtime source list changed; review the public source allowlist')
    actual_core = {path.name for path in (root / 'src/core/remote_pairing').iterdir()
                   if path.suffix in {'.cpp', '.h'} or path.name in {'CMakeLists.txt', 'boringssl.json'}}
    if actual_core != set(CORE):
        raise ValueError('Pairing core inputs changed; review the public source allowlist')
    for name, data in files.items():
        if not name.endswith(('.cpp', '.h')):
            continue
        for included in re.findall(rb'^\s*#include\s+"([^"\n]+)"', data, re.M):
            header = included.decode('utf-8')
            local = str(PurePosixPath(name).parent / header)
            if local not in files and 'src/core/' + header not in files:
                raise ValueError(f'Missing public C++ header: {name}: {header}')


def source_files(root: Path) -> dict[str, bytes]:
    """Return paths relative to spatial-wall/remote-source/, independent of runtime caches."""
    root = root.resolve()
    names = (tuple('data/remote-loader/' + name for name in LOADER)
             + tuple('src/core/remote_pairing/' + name for name in CORE)
             + tuple('tools/remote-pairing-wasm/' + name for name in WASM) + FIXED)
    if len(names) != len(set(names)):
        raise ValueError('Duplicate public source allowlist entry')
    result = {}
    for name in names:
        path = root / name
        if not path.is_file() or not path.resolve().is_relative_to(root):
            raise ValueError(f'Missing or redirected loader source: {name}')
        for component in (path, *path.parents):
            if component == root:
                break
            if component.is_symlink() or getattr(component, 'is_junction', lambda: False)():
                raise ValueError(f'Redirected loader source: {name}')
        data = path.read_bytes()
        if path.suffix != '.png':
            data = data.replace(b'\r\n', b'\n')
        if not data:
            raise ValueError(f'Empty loader source: {name}')
        result[name] = data
    public_key = result['data/remote-loader/product-key.pem']
    if not re.fullmatch(rb'-----BEGIN PUBLIC KEY-----\n[A-Za-z0-9+/=\n]+-----END PUBLIC KEY-----\n?', public_key):
        raise ValueError('Expected only the product public verification key')
    check_dependencies(root, result)
    # The repository root LICENSE belongs to the retired OBS plugin and is never read.
    result['LICENSE'] = result['data/remote-loader/LICENSE']
    result['README.md'] = result['tools/remote-ui/source/README.md']
    result['README.ja.md'] = result['tools/remote-ui/source/README.ja.md']
    result['.gitattributes'] = b'* text eol=lf\n*.png binary\n'
    result['.gitignore'] = b'build/\ndeps/\n__pycache__/\nlocal.properties\n*.keystore\n*.jks\n*.p12\n'
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true', required=True, help='Validate without writing any files')
    parser.parse_args()
    files = source_files(ROOT)
    print(f'Checked {len(files)} public loader source files ({sum(map(len, files.values()))} bytes); no files published')
