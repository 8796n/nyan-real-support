#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
# Copyright (C) 2026 8796n <info@8796.jp>
"""Build and verify the fixed official loader release; never publish it."""
import argparse
import base64
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / 'data/remote-loader'
WASM = ROOT / 'build/remote-loader'
SOURCE_ASSETS = (
    'entry-capture.js', 'entry.css', 'entry.mjs', 'connection-data.mjs', 'device-store.mjs',
    'pairing-client.mjs', 'pairing.mjs', 'device-frame.mjs', 'device-connection.mjs', 'ui-frame.mjs',
    'frame-bridge.js', 'frame.js', 'frame.html', 'frame-source.mjs', 'identity.mjs', 'transport.mjs', 'verify-ui.mjs',
    'browser-support.mjs', 'offline.mjs', 'product-key.pem', 'LICENSE',
)
GENERATED = ('pairing-core.mjs', 'pairing-core.wasm', 'remote-pairing-wasm.txt', 'boringssl.txt')
ICON_ASSETS = ('icon-192.png', 'icon-512.png')
FORMAT = 'spatial-wall.loader.v3'
LEGACY_FORMAT = 'spatial-wall.loader.v2'

def immutable_asset_names(format_name):
    if format_name == LEGACY_FORMAT:
        return SOURCE_ASSETS + GENERATED
    if format_name == FORMAT:
        return SOURCE_ASSETS + GENERATED + ICON_ASSETS
    raise ValueError('Unknown loader manifest format')

def digest(data):
    return hashlib.sha256(data).hexdigest()

def text_bytes(path):
    return path.read_bytes().replace(b'\r\n', b'\n')

def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=True).encode('ascii')

def wasm_inputs():
    files = [path for path in (ROOT / 'src/core/remote_pairing').glob('*')
             if path.suffix in ('.cpp', '.h') or path.name in ('CMakeLists.txt', 'boringssl.json')]
    files += [ROOT / relative for relative in (
        'src/core/ws_message_validation.h',
        'tools/remote-pairing-wasm/build.ps1', 'tools/remote-pairing-wasm/CMakeLists.txt',
        'tools/remote-pairing-wasm/entry.cpp', 'tools/remote-pairing-wasm/runtime.json',
        'tools/remote-pairing-wasm/licenses.py', 'data/remote-loader/pairing.mjs',
        'licenses/remote-pairing-wasm.txt', 'licenses/boringssl.txt', 'scripts/setup-remote-pairing.py',
    )]
    return {str(path.relative_to(ROOT)).replace('\\', '/'): digest(text_bytes(path))
            for path in sorted(files) if path.is_file()}

def record_wasm(directory, phase):
    directory.mkdir(parents=True, exist_ok=True)
    start, receipt = directory / 'pairing-build-start.json', directory / 'pairing-build.json'
    if phase == 'start':
        receipt.unlink(missing_ok=True)
        start.write_bytes(canonical(wasm_inputs()))
        return
    if json.loads(start.read_bytes()) != wasm_inputs():
        raise ValueError('Pairing sources changed during the build; rebuild before staging')
    receipt.write_bytes(canonical({'v': 1, 'inputs': wasm_inputs(),
                                  'outputs': {name: digest((directory / name).read_bytes()) for name in GENERATED}}))
    start.unlink()

def checked_wasm(directory):
    receipt = json.loads((directory / 'pairing-build.json').read_bytes())
    data = {name: (directory / name).read_bytes() for name in GENERATED}
    if receipt != {'v': 1, 'inputs': wasm_inputs(), 'outputs': {name: digest(value) for name, value in data.items()}}:
        raise ValueError('Wasm build receipt is stale or differs from its tested output; run the Wasm build helper')
    if data['pairing-core.wasm'][:8] != b'\0asm\1\0\0\0':
        raise ValueError('Invalid Wasm artifact')
    for name in ('remote-pairing-wasm.txt', 'boringssl.txt'):
        if data[name] != (ROOT / 'licenses' / name).read_bytes():
            raise ValueError('Wasm notices differ from their reviewed originals')
    return data

def content_type(name):
    return {'.html': 'text/html; charset=utf-8', '.mjs': 'text/javascript; charset=utf-8',
            '.js': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8',
            '.wasm': 'application/wasm', '.png': 'image/png',
            '.webmanifest': 'application/manifest+json; charset=utf-8'}.get(Path(name).suffix, 'text/plain; charset=utf-8')

def release_files(wasm_directory=WASM):
    raw = {name: text_bytes(SOURCE / name) for name in SOURCE_ASSETS}
    raw.update({name: (SOURCE / name).read_bytes() for name in ICON_ASSETS})
    # Verify exact build bytes first, then use the same LF text format as public Git blobs.
    raw.update({name: data if name.endswith('.wasm') else data.replace(b'\r\n', b'\n')
                for name, data in checked_wasm(wasm_directory).items()})
    index = text_bytes(SOURCE / 'index.html')
    app_manifest = text_bytes(SOURCE / 'manifest.webmanifest')
    template = text_bytes(SOURCE / 'service-worker.js.in')
    inputs = {name: digest(data) for name, data in raw.items()}
    inputs.update({'index.html': digest(index), 'manifest.webmanifest': digest(app_manifest),
                   'service-worker.js.in': digest(template)})
    release = digest(canonical(inputs))
    prefix = f'assets/{release}/'
    # Only these fixed official scripts inherit inline permission in the Blob frame.
    hashes = []
    for name in ('frame-bridge.js', 'frame.js'):
        if b'</script' in raw[name].lower():
            raise ValueError('Unexpected sandbox bootstrap script terminator')
        hashes.append(b"'sha256-" + base64.b64encode(hashlib.sha256(raw[name]).digest()) + b"'")
    script_policy = b"script-src 'self' blob: "
    if index.count(script_policy) != 1:
        raise ValueError('Unexpected parent script policy')
    index = index.replace(script_policy, script_policy + b' '.join(hashes) + b' ')
    for name in ('entry-capture.js', 'entry.css', 'entry.mjs', 'LICENSE', 'remote-pairing-wasm.txt', 'boringssl.txt'):
        before, after = ('./' + name).encode(), ('./' + prefix + name).encode()
        if index.count(before) != 1:
            raise ValueError('Unexpected entry asset reference: ' + name)
        index = index.replace(before, after)
    files = {prefix + name: data for name, data in raw.items()}
    files['index.html'] = index
    app = json.loads(app_manifest)
    if [icon['src'] for icon in app['icons']] != ['./' + name for name in ICON_ASSETS]:
        raise ValueError('Unexpected app manifest icon references')
    for icon, name in zip(app['icons'], ICON_ASSETS):
        icon['src'] = './' + prefix + name
    files['manifest.webmanifest'] = canonical(app) + b'\n'
    assets = [{'path': name, 'size': len(data), 'sha256': digest(data), 'type': content_type(name)}
              for name, data in sorted(files.items())]
    if any(not 0 < item['size'] <= 1024 * 1024 for item in assets):
        raise ValueError('Offline asset exceeds the reviewed 1 MiB limit')
    if template.count(b'/*__RELEASE__*/') != 1 or template.count(b'/*__ASSETS__*/') != 1:
        raise ValueError('Invalid service worker template')
    files['sw.js'] = template.replace(b'/*__RELEASE__*/', canonical(release)).replace(b'/*__ASSETS__*/', canonical(assets))
    # Keep immutable releases auditable after the mutable entry advances.
    files[prefix + 'release-manifest.json'] = canonical({'format': FORMAT, 'release': release,
        'files': {name: {'size': len(data), 'sha256': digest(data)} for name, data in sorted(raw.items())}}) + b'\n'
    manifest = {'format': FORMAT, 'release': release, 'inputs': inputs,
                'files': {name: {'size': len(data), 'sha256': digest(data)} for name, data in sorted(files.items())}}
    files['loader-manifest.json'] = canonical(manifest) + b'\n'
    return files

def safe_file(directory, name):
    path = directory / name
    if directory.resolve() not in path.resolve().parents:
        raise ValueError('Loader path escaped its output directory')
    return path

def owned_names(manifest):
    format_name = manifest.get('format')
    payloads = immutable_asset_names(format_name)
    release = manifest.get('release', '')
    if not isinstance(release, str) or len(release) != 64 or any(c not in '0123456789abcdef' for c in release):
        raise ValueError('Unknown staged loader manifest')
    names = set(manifest.get('files', {}))
    expected = {'index.html', 'sw.js'} | {f'assets/{release}/{name}' for name in payloads + ('release-manifest.json',)}
    if format_name == FORMAT:
        expected.add('manifest.webmanifest')
    if names != expected:
        raise ValueError('Unexpected staged loader file list')
    return names | {'loader-manifest.json'}

def verify_stage(directory, wasm_directory=WASM):
    expected = release_files(wasm_directory)
    actual = {path.relative_to(directory).as_posix() for path in directory.rglob('*') if path.is_file()}
    if actual != set(expected):
        raise ValueError('Staged loader has missing or extra files')
    for name, data in expected.items():
        if safe_file(directory, name).read_bytes() != data:
            raise ValueError('Staged loader differs from its reviewed sources: ' + name)
    return expected

def stage(directory, wasm_directory=WASM):
    expected = release_files(wasm_directory)
    previous = directory / 'loader-manifest.json'
    old = owned_names(json.loads(previous.read_bytes())) if previous.exists() else set()
    actual = {path.relative_to(directory).as_posix() for path in directory.rglob('*') if path.is_file()}
    if actual - old:
        raise ValueError('Output contains unknown files; refusing to overwrite or publish them')
    for name, data in expected.items():
        path = safe_file(directory, name); path.parent.mkdir(parents=True, exist_ok=True); path.write_bytes(data)
    for name in old - set(expected):
        safe_file(directory, name).unlink(missing_ok=True)
    verify_stage(directory, wasm_directory)
    return expected

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--wasm-dir', type=Path, default=WASM)
    parser.add_argument('--output', type=Path, default=WASM / 'site')
    parser.add_argument('--check', action='store_true')
    parser.add_argument('--record-wasm', choices=('start', 'finish'))
    args = parser.parse_args()
    if args.record_wasm:
        record_wasm(args.wasm_dir, args.record_wasm)
        print('Recorded tested Wasm build inputs/outputs: ' + args.record_wasm)
    else:
        files = verify_stage(args.output, args.wasm_dir) if args.check else stage(args.output, args.wasm_dir)
        print(f'Verified {len(files)} fixed loader files; no publication performed')

if __name__ == '__main__':
    main()
