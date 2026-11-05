# Shared discovery for setup and build; loading this file has no side effects.
function Test-WindowsToolchain {
  param([string]$Path)
  $versionFile = Join-Path $Path 'VC\Auxiliary\Build\Microsoft.VCToolsVersion.default.txt'
  if (-not (Test-Path -LiteralPath $versionFile)) { return $false }
  $versionText = (Get-Content -LiteralPath $versionFile -Raw).Trim()
  $version = $null
  if (-not [version]::TryParse($versionText, [ref]$version) -or
      $version -lt [version]'14.51') { return $false }
  foreach ($relative in @(
      'VC\Auxiliary\Build\vcvars64.bat',
      "VC\Tools\MSVC\$versionText\bin\Hostx64\x64\cl.exe",
      'Common7\IDE\CommonExtensions\Microsoft\CMake\CMake\bin\cmake.exe',
      'Common7\IDE\CommonExtensions\Microsoft\CMake\Ninja\ninja.exe')) {
    if (-not (Test-Path -LiteralPath (Join-Path $Path $relative) -PathType Leaf)) {
      return $false
    }
  }
  return $true
}

function Get-VisualStudioCandidates {
  $vswhere = "${env:ProgramFiles(x86)}\Microsoft Visual Studio\Installer\vswhere.exe"
  if (Test-Path -LiteralPath $vswhere) {
    # Do not accept VS 2022 just because it has an x64 compiler. Do not let an
    # incomplete preferred Community install hide a complete Build Tools install.
    & $vswhere -products '*' -version '[18.0,)' -requires `
      Microsoft.VisualStudio.Component.VC.Tools.x86.x64 `
      Microsoft.VisualStudio.Component.VC.CMake.Project -property installationPath
    if ($LASTEXITCODE -ne 0) { throw "vswhere failed ($LASTEXITCODE)" }
  }
}

function Find-VsPath {
  foreach ($candidate in @(Get-VisualStudioCandidates)) {
    if ($candidate -and (Test-WindowsToolchain $candidate)) { return $candidate }
  }
  return $null
}

function Find-WindowsSdk {
  param([string]$Root)
  if (-not $Root) {
    $Root = (Get-ItemProperty -LiteralPath 'HKLM:\SOFTWARE\Microsoft\Windows Kits\Installed Roots' `
      -Name KitsRoot10 -ErrorAction SilentlyContinue).KitsRoot10
  }
  if (-not $Root) { return $null }
  $versions = @(Get-ChildItem -LiteralPath (Join-Path $Root 'Include') -Directory -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -match '^10\.0\.\d+\.\d+$' } | Sort-Object { [version]$_.Name } -Descending)
  foreach ($candidate in $versions) {
    $version = $candidate.Name
    if ([version]$version -lt [version]'10.0.26100.0') { continue }
    $complete = $true
    foreach ($file in @("Include\$version\um\Windows.h", "Include\$version\ucrt\stdio.h",
        "Include\$version\cppwinrt\winrt\Windows.Devices.Display.Core.h",
        "Lib\$version\um\x64\WindowsApp.lib", "Lib\$version\ucrt\x64\ucrt.lib",
        "bin\$version\x64\rc.exe", "bin\$version\x64\mt.exe")) {
      if (-not (Test-Path -LiteralPath (Join-Path $Root $file) -PathType Leaf)) { $complete = $false; break }
    }
    if ($complete) { return $version }
  }
  return $null
}
