<# Sets up the development environment (project-local Python venv; nothing is installed system-wide). #>
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$Root = Split-Path -Parent $PSScriptRoot
if (-not $env:JAVA_HOME) {
    foreach ($c in @('C:\Program Files\Java\jdk-21', 'C:\Program Files\Eclipse Adoptium\jdk-21')) {
        if (Test-Path (Join-Path $c 'bin\java.exe')) { $env:JAVA_HOME = $c; break }
    }
}

$py = Get-Command python -ErrorAction SilentlyContinue
if (-not $py) { throw 'Python 3.12+ is required on PATH for development.' }
$venv = Join-Path $Root 'python\.venv'
if (-not (Test-Path $venv)) { & python -m venv $venv }
& (Join-Path $venv 'Scripts\python.exe') -m pip install --disable-pip-version-check -e "$(Join-Path $Root 'python')[dev,zstd]"
if (-not $env:JAVA_HOME) { Write-Warning 'JAVA_HOME is not set; install a JDK 21+ for building.' }
Write-Host "Development environment ready. Set IDLESTOW_PYTHON=$(Join-Path $venv 'Scripts\python.exe') to use the venv for the worker."
