param(
    [string]$JavaHome,
    [string]$Maven = 'mvn',
    [switch]$Build,
    [string]$LegacyRealdata,
    [int]$Limit = 60,
    [ValidateSet('single','timeline')][string]$LegacyExperiment = 'single',
    [int]$Trials = 12,
    [int]$Seed = 20261119,
    [string]$ActualFares,
    [string]$OutputPrefix = 'synthetic'
)
$ErrorActionPreference = 'Stop'
$experimentArgs = @((Join-Path $PSScriptRoot 'run.py'), '--seed', "$Seed", '--trials', "$Trials", '--limit', "$Limit", '--output-prefix', $OutputPrefix)
if ($JavaHome) { $experimentArgs += @('--java-home', $JavaHome) }
if ($Build) { $experimentArgs += @('--build', '--maven', $Maven) }
if ($LegacyRealdata) { $experimentArgs += @('--legacy-realdata', $LegacyRealdata, '--legacy-experiment', $LegacyExperiment) }
if ($ActualFares) { $experimentArgs += @('--actual-fares', $ActualFares) }
& python @experimentArgs
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& python (Join-Path $PSScriptRoot 'fares.py') @($ActualFares | Where-Object { $_ })
exit $LASTEXITCODE
