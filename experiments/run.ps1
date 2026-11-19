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
    [int]$Bootstrap = 1000,
    [string]$OutputPrefix = 'synthetic'
)
$ErrorActionPreference = 'Stop'
$experimentArgs = @((Join-Path $PSScriptRoot 'run.py'), '--seed', "$Seed", '--trials', "$Trials", '--limit', "$Limit", '--output-prefix', $OutputPrefix)
$experimentArgs += @('--bootstrap', "$Bootstrap")
if ($JavaHome) { $experimentArgs += @('--java-home', $JavaHome) }
if ($Build) { $experimentArgs += @('--build', '--maven', $Maven) }
if ($LegacyRealdata) { $experimentArgs += @('--legacy-realdata', $LegacyRealdata, '--legacy-experiment', $LegacyExperiment) }
if ($ActualFares) { $experimentArgs += @('--actual-fares', $ActualFares) }
& python @experimentArgs
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
# C5 --public is run first with the optional DuckDB environment. Do not overwrite it
# with an empty private CSV report. Old paired-CSV support remains in fares.py.
exit 0
