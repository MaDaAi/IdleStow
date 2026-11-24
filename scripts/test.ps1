<# Runs Java (unit, integration, native) and Python tests. GUI smoke tests need a desktop session. #>
param([switch]$NoGui)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$Root = Split-Path -Parent $PSScriptRoot
if (-not $env:JAVA_HOME) {
    foreach ($c in @('C:\Program Files\Java\jdk-21', 'C:\Program Files\Eclipse Adoptium\jdk-21')) {
        if (Test-Path (Join-Path $c 'bin\java.exe')) { $env:JAVA_HOME = $c; break }
    }
}

Push-Location (Join-Path $Root 'python')
try {
    & python -m ruff check .
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    & python -m pytest -q
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
} finally { Pop-Location }
Push-Location $Root
try {
    $groups = if ($NoGui) { 'gui,benchmark' } else { 'benchmark' }
    & .\mvnw.cmd -B verify "-Dsurefire.excludedGroups=$groups"
    exit $LASTEXITCODE
} finally { Pop-Location }
