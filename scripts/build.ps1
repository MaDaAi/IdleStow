<# Builds all modules (skips tests). #>
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$Root = Split-Path -Parent $PSScriptRoot
if (-not $env:JAVA_HOME) {
    foreach ($c in @('C:\Program Files\Java\jdk-21', 'C:\Program Files\Eclipse Adoptium\jdk-21')) {
        if (Test-Path (Join-Path $c 'bin\java.exe')) { $env:JAVA_HOME = $c; break }
    }
}

Push-Location $Root
try { & .\mvnw.cmd -B package -DskipTests; if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE } } finally { Pop-Location }
