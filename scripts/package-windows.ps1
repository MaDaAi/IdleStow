<#
.SYNOPSIS
  Builds the IdleStow Windows portable application image (and an MSI when WiX is installed).

.DESCRIPTION
  1. Builds the Maven project (tests are run separately by scripts\test.ps1).
  2. Prepares a self-contained Python worker: the official CPython "embeddable" distribution
     (verified by SHA-256), the idlestow package, and the zstandard wheel.
  3. Runs jpackage to create dist\windows\IdleStow\ with a bundled Java runtime, IdleStow.exe (GUI)
     and IdleStow-cli.exe (console launcher used for --self-test / --smoke-test).
  4. Zips the image to dist\IdleStow-<version>-windows-x64-portable.zip.
  Nothing is installed system-wide. Downloads are cached in packaging\windows\cache (git-ignored).

.PARAMETER SkipBuild
  Reuse the existing Maven build output.
#>
[CmdletBinding()]
param(
    [switch]$SkipBuild
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$Root = Split-Path -Parent $PSScriptRoot
$Version = '0.1.0'
$PyVersion = '3.12.10'
$PyZipName = "python-$PyVersion-embed-amd64.zip"
$PyUrl = "https://www.python.org/ftp/python/$PyVersion/$PyZipName"
# SHA-256 of the official embeddable archive (pinned; verified on every build).
$PySha256 = (Get-Content -Raw (Join-Path $Root 'packaging\windows\python-embed.sha256')).Trim().Split(' ')[0]
$ZstdVersion = '0.25.0'

# The bundled runtime must be redistributable: use a pinned, checksum-verified Eclipse Temurin (OpenJDK,
# GPLv2 + Classpath Exception) unless IDLESTOW_PACKAGING_JDK points at another OpenJDK build.
$TemurinName = 'OpenJDK21U-jdk_x64_windows_hotspot_21.0.13.1_2.zip'
$TemurinUrl = 'https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.13.1%2B2/' + $TemurinName
$TemurinSha256 = 'bbebea1469996b276683c9299c227b294f85c847039c82633218186a4ad4fbf2'

$Build = Join-Path $Root 'packaging\windows\build'
$Cache = Join-Path $Root 'packaging\windows\cache'
New-Item -ItemType Directory -Force -Path $Cache | Out-Null


function Get-PackagingJdk {
    if ($env:IDLESTOW_PACKAGING_JDK) { return $env:IDLESTOW_PACKAGING_JDK }
    $zip = Join-Path $Cache $TemurinName
    $dir = Join-Path $Cache 'temurin-21'
    if (-not (Test-Path (Join-Path $dir 'bin\jpackage.exe'))) {
        if (-not (Test-Path $zip)) {
            Write-Host "Downloading $TemurinUrl"
            Invoke-WebRequest -UseBasicParsing -Uri $TemurinUrl -OutFile $zip
        }
        $h = (Get-FileHash -Algorithm SHA256 $zip).Hash.ToLowerInvariant()
        if ($h -ne $TemurinSha256) { Remove-Item $zip; throw "Temurin checksum mismatch ($h)" }
        $tmp = Join-Path $Cache 'temurin-extract'
        if (Test-Path $tmp) { Remove-Item -Recurse -Force $tmp }
        Expand-Archive -Path $zip -DestinationPath $tmp
        $inner = Get-ChildItem $tmp -Directory | Select-Object -First 1
        if (Test-Path $dir) { Remove-Item -Recurse -Force $dir }
        Move-Item $inner.FullName $dir
        Remove-Item -Recurse -Force $tmp
    }
    return $dir
}

$Jdk = Get-PackagingJdk
$env:JAVA_HOME = $Jdk
Write-Host "Using JDK: $Jdk"
$Dist = Join-Path $Root 'dist'
$ImageDir = Join-Path $Dist 'windows'

if (Test-Path $Build) { Remove-Item -Recurse -Force $Build }
New-Item -ItemType Directory -Force -Path $Build | Out-Null

# ---------------------------------------------------------------- 1. Maven build
if (-not $SkipBuild) {
    Push-Location $Root
    try {
        & (Join-Path $Root 'mvnw.cmd') -q -B package -DskipTests
        if ($LASTEXITCODE -ne 0) { throw "Maven build failed ($LASTEXITCODE)" }
    } finally { Pop-Location }
}
$InputDir = Join-Path $Build 'input'
New-Item -ItemType Directory -Force -Path $InputDir | Out-Null
Copy-Item (Join-Path $Root 'app\target\idlestow.jar') $InputDir
Copy-Item (Join-Path $Root 'app\target\lib\*.jar') $InputDir

# ---------------------------------------------------------------- 2. Python worker
$PyZip = Join-Path $Cache $PyZipName
if (-not (Test-Path $PyZip)) {
    Write-Host "Downloading $PyUrl"
    Invoke-WebRequest -UseBasicParsing -Uri $PyUrl -OutFile $PyZip
}
$actual = (Get-FileHash -Algorithm SHA256 $PyZip).Hash.ToLowerInvariant()
if ($actual -ne $PySha256.ToLowerInvariant()) {
    Remove-Item $PyZip
    throw "SHA-256 mismatch for $PyZipName (expected $PySha256, got $actual). Download removed."
}
$Worker = Join-Path $Build 'worker'
$PyDir = Join-Path $Worker 'python'
Expand-Archive -Path $PyZip -DestinationPath $PyDir
# Embeddable Python ignores PYTHONPATH when a ._pth file exists, so list the worker paths explicitly.
$pth = Get-ChildItem $PyDir -Filter 'python*._pth' | Select-Object -First 1
$zipName = (Get-ChildItem $PyDir -Filter 'python3*.zip' | Select-Object -First 1).Name
Set-Content -Encoding ascii -Path $pth.FullName -Value @($zipName, '.', '..\app', 'Lib\site-packages')
$Site = Join-Path $PyDir 'Lib\site-packages'
New-Item -ItemType Directory -Force -Path $Site | Out-Null
& python -m pip install --disable-pip-version-check --no-deps --only-binary=:all: --platform win_amd64 `
    --python-version 3.12 --implementation cp --target $Site "zstandard==$ZstdVersion"
if ($LASTEXITCODE -ne 0) { throw 'Failed to fetch the zstandard wheel' }
$App = Join-Path $Worker 'app'
New-Item -ItemType Directory -Force -Path $App | Out-Null
Copy-Item -Recurse (Join-Path $Root 'python\idlestow') (Join-Path $App 'idlestow')
Get-ChildItem -Recurse -Directory -Filter '__pycache__' $App | Remove-Item -Recurse -Force
# Smoke-check the bundled worker before packaging.
$probe = & (Join-Path $PyDir 'python.exe') -s -c "import sys; sys.path.insert(0, r'$App'); import idlestow.worker, zstandard; print('worker-ok', zstandard.__version__)"
if ($LASTEXITCODE -ne 0 -or -not ("$probe" -match 'worker-ok')) { throw "Bundled worker probe failed: $probe" }
Write-Host "Bundled worker: $probe"

# ---------------------------------------------------------------- 3. jpackage
if (Test-Path $ImageDir) { Remove-Item -Recurse -Force $ImageDir }
$cliProps = Join-Path $Build 'cli.properties'
Set-Content -Encoding ascii -Path $cliProps -Value @('win-console=true')
$icon = Join-Path $Root 'packaging\windows\IdleStow.ico'
$common = @(
    '--name', 'IdleStow',
    '--app-version', $Version,
    '--vendor', 'MaDaAi',
    '--description', 'IdleStow - Idle files. Reclaimed space.',
    '--copyright', 'Copyright 2026 the IdleStow contributors. Apache License 2.0.',
    '--icon', $icon
)
$imageArgs = @(
    '--type', 'app-image',
    '--dest', $ImageDir,
    '--input', $InputDir,
    '--main-jar', 'idlestow.jar',
    '--main-class', 'io.github.madaai.idlestow.app.Launcher',
    '--add-modules', 'java.se,jdk.unsupported,jdk.management,jdk.crypto.ec,jdk.localedata,jdk.charsets,jdk.zipfs,jdk.accessibility',
    '--jlink-options', '--strip-debug --no-man-pages --no-header-files',
    '--java-options', '-Dfile.encoding=UTF-8',
    '--java-options', '--enable-native-access=ALL-UNNAMED',
    '--add-launcher', "IdleStow-cli=$cliProps"
) + $common
& (Join-Path $Jdk 'bin\jpackage.exe') @imageArgs
if ($LASTEXITCODE -ne 0) { throw "jpackage failed ($LASTEXITCODE)" }
$AppImage = Join-Path $ImageDir 'IdleStow'
Copy-Item -Recurse $Worker (Join-Path $AppImage 'worker')
foreach ($f in @('LICENSE', 'NOTICE', 'THIRD-PARTY-NOTICES.md', 'README.md')) {
    $src = Join-Path $Root $f
    if (Test-Path $src) { Copy-Item $src $AppImage }
}

# ---------------------------------------------------------------- 4. archives / installer
$PortableZip = Join-Path $Dist "IdleStow-$Version-windows-x64-portable.zip"
if (Test-Path $PortableZip) { Remove-Item $PortableZip }
Compress-Archive -Path $AppImage -DestinationPath $PortableZip
Write-Host "Portable image: $AppImage"
Write-Host "Portable zip:   $PortableZip"

$wix = (Get-Command 'wix.exe' -ErrorAction SilentlyContinue) -or (Get-Command 'candle.exe' -ErrorAction SilentlyContinue)
if ($wix) {
    & (Join-Path $Jdk 'bin\jpackage.exe') --type msi --dest $Dist --app-image $AppImage `
        --win-menu --win-shortcut --win-per-user-install --win-dir-chooser @common
    if ($LASTEXITCODE -ne 0) { throw "MSI packaging failed ($LASTEXITCODE)" }
    Write-Host "MSI installer written to $Dist"
} else {
    Write-Warning 'WiX Toolset not found: the MSI installer was NOT built. The portable image is complete.'
}
