<# Generates SYNTHETIC test data (never point this at real folders). #>
param([Parameter(Mandatory)][string]$Out, [ValidateSet('mixed','benchmark')][string]$Preset = 'mixed', [int]$Files = 1000, [int]$Seed = 1)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$Root = Split-Path -Parent $PSScriptRoot
if (-not $env:JAVA_HOME) {
    foreach ($c in @('C:\Program Files\Java\jdk-21', 'C:\Program Files\Eclipse Adoptium\jdk-21')) {
        if (Test-Path (Join-Path $c 'bin\java.exe')) { $env:JAVA_HOME = $c; break }
    }
}

$env:PYTHONPATH = Join-Path $Root 'python'
& python -m idlestow.fixtures --out $Out --preset $Preset --files $Files --seed $Seed
exit $LASTEXITCODE
