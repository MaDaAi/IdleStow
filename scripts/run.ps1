<# Runs the desktop app from the build output. Uses a throw-away data directory unless -DataDir is given. #>
param([string]$DataDir = '', [switch]$ReadOnly)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$Root = Split-Path -Parent $PSScriptRoot
if (-not $env:JAVA_HOME) {
    foreach ($c in @('C:\Program Files\Java\jdk-21', 'C:\Program Files\Eclipse Adoptium\jdk-21')) {
        if (Test-Path (Join-Path $c 'bin\java.exe')) { $env:JAVA_HOME = $c; break }
    }
}

$jar = Join-Path $Root 'app\target\idlestow.jar'
if (-not (Test-Path $jar)) { & (Join-Path $PSScriptRoot 'build.ps1') }
if (-not $DataDir) { $DataDir = Join-Path $Root 'target\dev-data' }
$argsList = @("-Didlestow.python.dir=$(Join-Path $Root 'python')", '-jar', $jar, '--data-dir', $DataDir)
if ($ReadOnly) { $argsList += '--read-only' }
& (Join-Path $env:JAVA_HOME 'bin\java.exe') @argsList
