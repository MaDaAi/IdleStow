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
$Version = '0.1.1'
$PyVersion = '3.12.10'
$PyZipName = "python-$PyVersion-embed-amd64.zip"
$PyUrl = "https://www.python.org/ftp/python/$PyVersion/$PyZipName"
# SHA-256 of the official embeddable archive (pinned; verified on every build).
$PySha256 = (Get-Content -Raw (Join-Path $Root 'packaging\windows\python-embed.sha256')).Trim().Split(' ')[0]
$ZstdVersion = '0.25.0'
# SHA-256 of zstandard-0.25.0-cp312-cp312-win_amd64.whl as published on PyPI (verified by pip --require-hashes).
$ZstdWheelSha256 = 'ffef5a74088f1e09947aecf91011136665152e0b4b359c42be3373897fb39b01'

# The bundled runtime must be redistributable: use a pinned, checksum-verified Eclipse Temurin (OpenJDK,
# GPLv2 + Classpath Exception) unless IDLESTOW_PACKAGING_JDK points at another OpenJDK build.
$TemurinName = 'OpenJDK21U-jdk_x64_windows_hotspot_21.0.13.1_2.zip'
$TemurinUrl = 'https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.13.1%2B2/' + $TemurinName
$TemurinSha256 = 'bbebea1469996b276683c9299c227b294f85c847039c82633218186a4ad4fbf2'

$Build = Join-Path $Root 'packaging\windows\build'
$Cache = Join-Path $Root 'packaging\windows\cache'
New-Item -ItemType Directory -Force -Path $Cache | Out-Null


function Get-PackagingJdk {
    if ($env:IDLESTOW_PACKAGING_JDK) {
        # Only OpenJDK builds that may be redistributed are accepted (e.g. not Oracle JDK under NFTC/OTN).
        $rel = Get-Content (Join-Path $env:IDLESTOW_PACKAGING_JDK 'release') -ErrorAction Stop
        if (-not ($rel -match 'IMPLEMENTOR="(Eclipse Adoptium|Microsoft|Azul Systems, Inc.|Amazon.com Inc.|Red Hat, Inc.|BellSoft)"')) {
            throw "IDLESTOW_PACKAGING_JDK is not a known redistributable OpenJDK build: $($env:IDLESTOW_PACKAGING_JDK)"
        }
        return $env:IDLESTOW_PACKAGING_JDK
    }
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
    # Maven's copy-dependencies never removes old jars, so an upgraded library would otherwise be shipped
    # next to (and loaded before) its replacement. Start from an empty lib folder.
    $LibDir = Join-Path $Root 'app\target\lib'
    if (Test-Path $LibDir) { Remove-Item -Recurse -Force $LibDir }
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
# Refuse to package two versions of the same library (stale jars from an earlier build).
$dupes = Get-ChildItem $InputDir -Filter '*.jar' |
    Group-Object { $_.BaseName -replace '-\d+(\.\d+)*', '' } | Where-Object { $_.Count -gt 1 }
if ($dupes) {
    throw "Duplicate library versions in the package input: $(($dupes | ForEach-Object { $_.Group.Name }) -join ', ')"
}

# The package is Windows-only: drop the native libraries of other platforms that the cross-platform jars carry
# (sqlite-jdbc ships ~20 targets, JNA ~25). The Windows natives are kept untouched. This only removes zip entries.
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
function Remove-JarEntries([string]$Jar, [string[]]$DropPrefixes) {
    $tmp = "$Jar.slim"
    $src = [System.IO.Compression.ZipFile]::OpenRead($Jar)
    $dst = [System.IO.Compression.ZipFile]::Open($tmp, 'Create')
    $kept = 0; $dropped = 0
    try {
        foreach ($e in $src.Entries) {
            $drop = $false
            foreach ($p in $DropPrefixes) { if ($e.FullName.StartsWith($p, [StringComparison]::Ordinal)) { $drop = $true; break } }
            if ($drop) { $dropped++; continue }
            $n = $dst.CreateEntry($e.FullName, [System.IO.Compression.CompressionLevel]::Optimal)
            $n.LastWriteTime = $e.LastWriteTime
            if ($e.Length -gt 0) {
                $in = $e.Open(); $out = $n.Open()
                try { $in.CopyTo($out) } finally { $out.Dispose(); $in.Dispose() }
            }
            $kept++
        }
    } finally { $dst.Dispose(); $src.Dispose() }
    Move-Item -Force $tmp $Jar
    Write-Host "Slimmed $(Split-Path -Leaf $Jar): kept $kept entries, dropped $dropped"
}
$sqliteJar = Get-ChildItem $InputDir -Filter 'sqlite-jdbc-*.jar' | Select-Object -First 1
$sqliteDrop = @('Linux', 'Linux-Musl', 'Linux-Android', 'Mac', 'FreeBSD') | ForEach-Object { "org/sqlite/native/$_/" }
Remove-JarEntries $sqliteJar.FullName $sqliteDrop
$jnaJar = Get-ChildItem $InputDir -Filter 'jna-*.jar' | Where-Object { $_.Name -match '^jna-\d' } | Select-Object -First 1
$jnaDrop = @('aix-ppc', 'aix-ppc64', 'darwin-x86-64', 'darwin-aarch64', 'dragonflybsd-x86-64', 'freebsd-x86', 'freebsd-x86-64',
    'freebsd-aarch64', 'linux-x86', 'linux-x86-64', 'linux-arm', 'linux-armel', 'linux-aarch64', 'linux-ppc', 'linux-ppc64le',
    'linux-mips64el', 'linux-loongarch64', 'linux-s390x', 'linux-riscv64', 'openbsd-x86-64', 'sunos-x86', 'sunos-x86-64',
    'sunos-sparc', 'sunos-sparcv9', 'android-aarch64', 'android-arm', 'android-x86', 'android-x86-64') | ForEach-Object { "com/sun/jna/$_/" }
Remove-JarEntries $jnaJar.FullName $jnaDrop

# ---------------------------------------------------------------- jlink module set
# Everything the app, JavaFX, SQLite JDBC, JNA, logback, Jackson and the updater (java.net.http over HTTPS) need.
# jdeps verifies it below, so a new dependency that needs another JDK module fails the build instead of the app.
#   java.base          core (incl. the default cacerts truststore)
#   java.desktop       JavaFX graphics, AWT tray   java.logging, java.xml, java.naming, java.sql: logback/Jackson/JDBC
#   java.net.http      updater HTTP client         jdk.crypto.ec: TLS elliptic-curve suites
#   jdk.jfr            referenced by javafx.base   jdk.management: memory/OS beans   jdk.unsupported: sun.misc.Unsafe
#   jdk.localedata     non-English locale data     jdk.charsets: extended charsets (console/worker code pages)
# Deliberately absent: jdk.zipfs (nothing uses it), jdk.crypto.cryptoki, jdk.accessibility, java.se leftovers
# (rmi, scripting, jgss, sasl, xml.crypto, sql.rowset, compiler, instrument ...).
$JlinkModules = @('java.base', 'java.desktop', 'java.logging', 'java.naming', 'java.net.http', 'java.sql', 'java.xml',
    'jdk.crypto.ec', 'jdk.jfr', 'jdk.localedata', 'jdk.charsets', 'jdk.management', 'jdk.unsupported')
$jarList = @(Get-ChildItem $InputDir -Filter '*.jar' | ForEach-Object { $_.FullName })
$needed = (& (Join-Path $Jdk 'bin\jdeps.exe') --multi-release 21 --ignore-missing-deps --print-module-deps $jarList 2>$null) -split ','
if ($LASTEXITCODE -ne 0) { throw "jdeps failed ($LASTEXITCODE)" }
$transitive = @('java.xml', 'java.logging', 'java.prefs', 'java.datatransfer')   # pulled in by the modules above
$missing = $needed | ForEach-Object { $_.Trim() } | Where-Object { $_ -and ($JlinkModules -notcontains $_) -and ($transitive -notcontains $_) }
if ($missing) { throw "jdeps requires JDK modules missing from the jlink module set: $($missing -join ', ')" }
Write-Host "jdeps module requirements: $($needed -join ',')"

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
$req = Join-Path $Build 'worker-requirements.txt'
Set-Content -Encoding ascii -Path $req -Value "zstandard==$ZstdVersion --hash=sha256:$ZstdWheelSha256"
& python -m pip install --disable-pip-version-check --no-deps --only-binary=:all: --platform win_amd64 `
    --python-version 3.12 --implementation cp --target $Site --require-hashes -r $req
if ($LASTEXITCODE -ne 0) { throw 'Failed to fetch the zstandard wheel' }
$App = Join-Path $Worker 'app'
New-Item -ItemType Directory -Force -Path $App | Out-Null
Copy-Item -Recurse (Join-Path $Root 'python\idlestow') (Join-Path $App 'idlestow')
Get-ChildItem -Recurse -Directory -Filter '__pycache__' $App | Remove-Item -Recurse -Force
# Pre-compile the worker. Hash-based .pyc files stay valid after unzipping (timestamps do not survive a zip round trip),
# so the worker does not recompile its modules on every start.
& (Join-Path $PyDir 'python.exe') -s -m compileall -q --invalidation-mode checked-hash $App
if ($LASTEXITCODE -ne 0) { throw 'compileall of the worker package failed' }
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
    '--copyright', 'Copyright 2026 Mansoor Alakoozai. Apache License 2.0.',
    '--icon', $icon
)
$imageArgs = @(
    '--type', 'app-image',
    '--dest', $ImageDir,
    '--input', $InputDir,
    '--main-jar', 'idlestow.jar',
    '--main-class', 'io.github.madaai.idlestow.app.Launcher',
    '--add-modules', ($JlinkModules -join ','),
    # --generate-cds-archive: the runtime image ships no default CDS archive, so JDK classes would be parsed on every start.
    '--jlink-options', '--strip-debug --no-man-pages --no-header-files --generate-cds-archive',
    '--java-options', '-Dfile.encoding=UTF-8',
    '--java-options', '--enable-native-access=ALL-UNNAMED',
    # Serial GC: measurably faster start and a smaller footprint for a desktop app whose data lives in SQLite, not on the heap.
    '--java-options', '-XX:+UseSerialGC',
    # Application class-data-sharing archive, created by the JVM on the first run on the user's machine and re-created
    # automatically when the jars change (e.g. after an update). It is NOT shipped: the JVM validates it against the absolute
    # jar paths of the machine that built it, so an archive from the build machine is useless after unzipping elsewhere.
    # If the folder is not writable the JVM just starts without it. -Xlog:cds*=off keeps dump warnings off the console.
    '--java-options', '-XX:+AutoCreateSharedArchive',
    '--java-options', '-XX:SharedArchiveFile=$APPDIR\IdleStow.jsa',
    '--java-options', '-Xlog:cds*=off',
    '--add-launcher', "IdleStow-cli=$cliProps"
) + $common
& (Join-Path $Jdk 'bin\jpackage.exe') @imageArgs
if ($LASTEXITCODE -ne 0) { throw "jpackage failed ($LASTEXITCODE)" }
$AppImage = Join-Path $ImageDir 'IdleStow'
# jlink writes a second CDS archive for heaps above 32 GB (no compressed oops). A desktop app never runs with such a heap
# and the JVM simply runs without CDS if it ever did, so it is not worth 12 MB.
Remove-Item -Force -ErrorAction SilentlyContinue (Join-Path $AppImage 'runtime\bin\server\classes_nocoops.jsa')
Copy-Item -Recurse $Worker (Join-Path $AppImage 'worker')
foreach ($f in @('LICENSE', 'NOTICE', 'THIRD-PARTY-NOTICES.md', 'README.md')) {
    $src = Join-Path $Root $f
    if (Test-Path $src) { Copy-Item $src $AppImage }
}

# Packaged-image gate: run the real console launcher once against a throw-away data dir under the build folder. This proves
# the trimmed runtime, the bundled worker and the JVM options work, and that the CDS archive can be created. The archive is
# then deleted so it is not shipped (see the java-options comment above).
$smokeRoot = Join-Path $Build 'smoke'
New-Item -ItemType Directory -Force -Path $smokeRoot | Out-Null
$prevEap = $ErrorActionPreference; $ErrorActionPreference = 'Continue'   # the JVM writes warnings to stderr
$smokeOut = & (Join-Path $AppImage 'IdleStow-cli.exe') --smoke-test --data-dir (Join-Path $smokeRoot 'data') 2>&1 | Out-String
$smokeExit = $LASTEXITCODE; $ErrorActionPreference = $prevEap
if ($smokeExit -ne 0 -or $smokeOut -notmatch 'SMOKE OK') { throw "Packaged smoke test failed ($smokeExit): $smokeOut" }
$jsa = Join-Path $AppImage 'app\IdleStow.jsa'
if (-not (Test-Path $jsa)) { Write-Warning 'The application CDS archive was not created by the training run.' }
Remove-Item -Force -ErrorAction SilentlyContinue $jsa
Write-Host 'Packaged smoke test: SMOKE OK'

# ---------------------------------------------------------------- 4. archives / installer
$PortableZip = Join-Path $Dist "IdleStow-$Version-windows-x64-portable.zip"
if (Test-Path $PortableZip) { Remove-Item $PortableZip }
Compress-Archive -Path $AppImage -DestinationPath $PortableZip
Write-Host "Portable image: $AppImage"
Write-Host "Portable zip:   $PortableZip"

# jpackage (JDK 21) drives WiX 3.x: candle.exe and light.exe must both be available.
$wix = (Get-Command 'candle.exe' -ErrorAction SilentlyContinue) -and (Get-Command 'light.exe' -ErrorAction SilentlyContinue)
if ($wix) {
    & (Join-Path $Jdk 'bin\jpackage.exe') --type msi --dest $Dist --app-image $AppImage `
        --win-menu --win-shortcut --win-per-user-install --win-dir-chooser @common
    if ($LASTEXITCODE -ne 0) { throw "MSI packaging failed ($LASTEXITCODE)" }
    Write-Host "MSI installer written to $Dist"
} else {
    Write-Warning 'WiX Toolset not found: the MSI installer was NOT built. The portable image is complete.'
}
