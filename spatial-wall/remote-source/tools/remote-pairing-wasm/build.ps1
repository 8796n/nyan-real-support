# SPDX-License-Identifier: MIT
# Copyright (C) 2026 8796n <info@8796.jp>
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$output = Join-Path $repo 'build/remote-loader'
$native = Join-Path $output 'pairing-native'
$wasm = Join-Path $output 'pairing-build'
$emsdk = Join-Path $repo 'deps/emsdk-spake2-probe'
New-Item -ItemType Directory -Force -Path $output | Out-Null
& python (Join-Path $repo 'scripts/setup-remote-pairing.py') --check
if ($LASTEXITCODE -ne 0) { throw 'Run scripts/setup-remote-pairing.py first.' }
& python (Join-Path $PSScriptRoot 'licenses.py')
if ($LASTEXITCODE -ne 0) { throw 'Wasm runtime license verification failed.' }
& python (Join-Path $repo 'tools/remote-ui/stage-loader.py') --record-wasm start --wasm-dir $output
if ($LASTEXITCODE -ne 0) { throw 'Wasm source snapshot failed.' }
. (Join-Path $repo 'scripts/windows-toolchain.ps1')
$vs = Find-VsPath
$sdk = Find-WindowsSdk
if (-not $vs -or -not $sdk) { throw 'The normal Windows build toolchain is required.' }
$cmake = Join-Path $vs 'Common7/IDE/CommonExtensions/Microsoft/CMake/CMake/bin/cmake.exe'
$ninja = Join-Path $vs 'Common7/IDE/CommonExtensions/Microsoft/CMake/Ninja/ninja.exe'
$vcvars = Join-Path $vs 'VC/Auxiliary/Build/vcvars64.bat'
$configure = "`"$cmake`" -S `"$PSScriptRoot`" -B `"$native`" -G Ninja -DCMAKE_BUILD_TYPE=Release -DCMAKE_MAKE_PROGRAM=`"$ninja`" -DCMAKE_C_COMPILER=cl -DCMAKE_CXX_COMPILER=cl"
$compile = "`"$cmake`" --build `"$native`" --target pairing_pipe --parallel 8"
cmd /c "`"$vcvars`" $sdk && set VSLANG=1033 && $configure && $compile" *> (Join-Path $output 'pairing-native-build.log')
if ($LASTEXITCODE -ne 0) {
    Get-Content (Join-Path $output 'pairing-native-build.log') -Tail 40
    throw 'Native pairing fixture build failed.'
}
Write-Output 'Native pairing fixture built.'
$compiler = Join-Path $emsdk 'upstream/emscripten/em++.exe'
if (-not (Test-Path -LiteralPath $compiler)) { throw 'The pinned Emscripten 6.0.13 SDK is required.' }
$node = (Get-Command node -ErrorAction Stop).Source.Replace('\', '/')
$sdkPath = $emsdk.Replace('\', '/')
$config = Join-Path $output '.emscripten-pairing'
$configText = @"
LLVM_ROOT = '$sdkPath/upstream/bin'
BINARYEN_ROOT = '$sdkPath/upstream'
EMSCRIPTEN_ROOT = '$sdkPath/upstream/emscripten'
NODE_JS = '$node'
CACHE = '$sdkPath/upstream/emscripten/cache'
"@
[IO.File]::WriteAllText($config, $configText, (New-Object System.Text.UTF8Encoding($false)))
$oldConfig = $env:EM_CONFIG
$env:EM_CONFIG = $config
try {
    $version = & $compiler --version
    if ($LASTEXITCODE -ne 0 -or $version[0] -notmatch '6\.0\.13') { throw 'Emscripten 6.0.13 is required.' }
    $toolchain = Join-Path $emsdk 'upstream/emscripten/cmake/Modules/Platform/Emscripten.cmake'
    & $cmake -S $PSScriptRoot -B $wasm -G Ninja -DCMAKE_BUILD_TYPE=Release "-DCMAKE_MAKE_PROGRAM=$ninja" "-DCMAKE_TOOLCHAIN_FILE=$toolchain"
    if ($LASTEXITCODE -ne 0) { throw 'Wasm pairing configure failed.' }
    & $cmake --build $wasm --target pairing_core --parallel 8 *> (Join-Path $output 'pairing-wasm-build.log')
    if ($LASTEXITCODE -ne 0) {
        Get-Content (Join-Path $output 'pairing-wasm-build.log') -Tail 40
        throw 'Wasm pairing build failed.'
    }
    & python (Join-Path $PSScriptRoot 'licenses.py') --map (Join-Path $wasm 'pairing-core.map') --output $output
    if ($LASTEXITCODE -ne 0) { throw 'Linked runtime license verification failed.' }
    Copy-Item -LiteralPath (Join-Path $wasm 'pairing-core.mjs'), (Join-Path $wasm 'pairing-core.wasm'), (Join-Path $repo 'data/remote-loader/pairing.mjs'), (Join-Path $PSScriptRoot 'browser.html'), (Join-Path $PSScriptRoot 'browser-checks.mjs') -Destination $output
    & $node (Join-Path $PSScriptRoot 'test.mjs') $output
    if ($LASTEXITCODE -ne 0) { throw 'Pairing native/Wasm checks failed.' }
    & python (Join-Path $PSScriptRoot 'test-licenses.py')
    if ($LASTEXITCODE -ne 0) { throw 'Wasm runtime license regression failed.' }
    & python (Join-Path $repo 'scripts/setup-remote-pairing.py') --check
    if ($LASTEXITCODE -ne 0) { throw 'BoringSSL source changed during the build.' }
    & python (Join-Path $repo 'tools/remote-ui/stage-loader.py') --record-wasm finish --wasm-dir $output
    if ($LASTEXITCODE -ne 0) { throw 'Wasm build source/output verification failed.' }
    Get-Item (Join-Path $output 'pairing-core.mjs'), (Join-Path $output 'pairing-core.wasm') | Select-Object Name, Length
} finally {
    $env:EM_CONFIG = $oldConfig
}
