# Spatial Wall browser entry source

[日本語](README.ja.md)

This directory contains the MIT-licensed browser entry, its WebAssembly pairing
source, and the tools needed to rebuild them. It preserves their original relative
paths. The files are published separately from the executable `remote/` release;
the service worker does not download or cache this source tree.

The browser entry authenticates a LAN PC, retains device credentials in the parent
origin, and loads the PC's signed remote UI into an isolated iframe. That PC UI,
the desktop application, product signing keys, and saved device data are not part
of this source distribution. `data/remote-loader/product-key.pem` is the public
verification key, not a signing key.

## What is included

- `data/remote-loader/`: unbundled JavaScript, HTML, CSS, the service worker template,
  home screen icons, public verification key, and MIT license.
- `src/core/remote_pairing/` and `src/core/ws_message_validation.h`: the shared C++
  pairing library and the header needed to compile its existing static target.
  The Wasm entry exports only the pairing API; unused server objects are not linked.
- `tools/remote-pairing-wasm/`: Wasm entry, native interoperability fixture, build
  helper, runtime manifest, notice checker, and the checks run by that helper.
- `scripts/`: fixed BoringSSL setup and Windows toolchain discovery.
- `tools/remote-ui/`: release staging and source allowlist checks.
- `licenses/`: complete BoringSSL and linked Wasm runtime notices.

## Rebuild on Windows

Copy or check out this entire directory, preserving paths, then run the following
from its root. No desktop source, Android SDK, account credential, or product
private key is needed. The provided helper currently supports Windows x64 with
PowerShell, Git, Python 3.11+, Node.js 24, and Visual Studio 2026 C++ tools
(MSVC 14.51+, CMake/Ninja component, Windows SDK 10.0.26100.0+).

First verify the source set and obtain the fixed BoringSSL dependency:

```powershell
python tools/remote-ui/source-loader.py --check
python tools/remote-ui/test-source-loader.py
python scripts/setup-remote-pairing.py
```

`src/core/remote_pairing/boringssl.json` pins the upstream revision and notice
hashes. Setup refuses modified dependencies and verifies the bundled notices.

Install the fixed official Emscripten SDK in its dedicated local directory:

```powershell
git clone https://github.com/emscripten-core/emsdk.git deps/emsdk-spake2-probe
git -C deps/emsdk-spake2-probe checkout --detach d7b4130688bfa348392b921ab04015aa15c4f154
$env:EMSDK_KEEP_DOWNLOADS = '1'
Push-Location deps/emsdk-spake2-probe
try { python emsdk.py install releases-eb132757325583b787fe9414b09afea9442b72ad-64bit }
finally { Pop-Location; Remove-Item Env:EMSDK_KEEP_DOWNLOADS }
```

This uses Emscripten 6.0.13, compiler revision
`e13ee988ce3d87a738dd8f3661d02223308c412a`. The fixed
[official Windows archive](https://storage.googleapis.com/webassembly/emscripten-releases-builds/win/eb132757325583b787fe9414b09afea9442b72ad/wasm-binaries.zip)
is 701,262,388 bytes (about 1.94 GB unpacked). Verify the retained archive's SHA-256
with `Get-FileHash -Algorithm SHA256 <archive-path>` before building:
`937b834b397e33645923794c1a20566a86c63ebc02b213e537c0a86dd380ab6c`.
This is our recorded hash of that official HTTPS download, not an independently
published upstream checksum. The same value and runtime notice hashes are in
`tools/remote-pairing-wasm/runtime.json`. SDK activation or a global PATH change
is unnecessary; the helper supplies a local `EM_CONFIG` and restores it afterward.

```powershell
& tools/remote-pairing-wasm/build.ps1
python tools/remote-ui/stage-loader.py
python tools/remote-ui/stage-loader.py --check
```

The helper builds the existing C++ core for native and Wasm, checks both protocol
roles across native/Wasm, rejects wrong codes, contexts, MACs, and proofs, and
verifies the linked runtime notices. It records the input and output hashes.
Staging refuses changed sources or outputs and creates a content-addressed release
under `build/remote-loader/site/`. All generated files stay under `build/` and
downloaded dependencies under `deps/`. These commands do not publish a site.

On 2026-11-05, fresh native and Wasm builds from the published source tree
(`0429a18`) succeeded in a separate directory, without adding desktop source or
private keys. The run reused verified, pinned dependency caches and passed the
8 source checks, 153 native/Wasm assertions, 3 notice tests, and release staging.
Fresh dependency downloads or installation on a clean Windows machine were not
part of this run.

The rebuilt release matched 24 of its 25 payload files byte for byte. Wasm contains
absolute BoringSSL build paths and differed in size and SHA-256 (94,588 bytes in
the separate directory versus 94,482 published bytes). Functional rebuilding is
verified; bit-for-bit Wasm reproducibility is not established.

## Running a modified copy

Serve the staged tree at `/spatial-wall/remote/` on a secure origin for development.
The web app manifest uses this path as both its installed identity and launch URL. Do not open the raw
`index.html` with `file://`. The current product accepts its official origin,
`https://support.nyanreal.jp`; changing hosts requires deliberate corresponding
PC origin-policy changes. Building these sources alone does not authorize another
origin to control an installed PC. The official entry currently targets supported
Chromium browsers; iOS browsers are not supported.

## License

Our source files in this distribution use the included [MIT license](LICENSE).
Dependencies retain their own terms: see
[BoringSSL notices](licenses/boringssl.txt) and
[Wasm runtime notices](licenses/remote-pairing-wasm.txt), including full upstream
copyright notices and LLVM exceptions. Retain these when distributing the build.
The desktop application has separate end-user terms.
