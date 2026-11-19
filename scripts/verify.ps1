param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$Maven = 'mvn',
    [switch]$WithMysql,
    [switch]$SkipInstall
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$previousLocation = Get-Location
$previousJavaHome = $env:JAVA_HOME
$previousPath = $env:PATH
$previousMysqlTests = $env:MYSQL_TESTS
$previousMysqlUrl = $env:MYSQL_TEST_URL
try {
    Set-Location -LiteralPath $projectRoot
    if ($JavaHome) {
        if (-not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin/java.exe'))) { throw 'JavaHome must point to a JDK 21 installation.' }
        $env:JAVA_HOME = $JavaHome
        $env:PATH = "$(Join-Path $JavaHome 'bin');$env:PATH"
    }
    $javaVersion = (& java -version 2>&1 | Out-String)
    if ($javaVersion -notmatch 'version "21\.') { throw "JDK 21 required. $javaVersion" }
    if ($WithMysql) {
        foreach ($name in @('DB_URL', 'MYSQL_TEST_URL', 'MYSQL_MIGRATION_TEST_URL', 'DB_USER', 'DB_PASSWORD')) {
            if (-not [Environment]::GetEnvironmentVariable($name)) { throw "WithMysql requires $name for an isolated test database." }
        }
        $env:MYSQL_TESTS = 'true'
    } else {
        $env:MYSQL_TESTS = 'false'
        $env:MYSQL_TEST_URL = $null
        Write-Output 'MySQL opt-in tests are disabled for this run. Use -WithMysql for full database verification.'
    }
    Push-Location -LiteralPath (Join-Path $projectRoot 'frontend')
    try {
        if (-not $SkipInstall) { & npm.cmd ci; if ($LASTEXITCODE -ne 0) { throw 'npm ci failed' } }
        & npm.cmd run lint; if ($LASTEXITCODE -ne 0) { throw 'Frontend lint failed' }
        & npm.cmd test; if ($LASTEXITCODE -ne 0) { throw 'Frontend tests failed' }
        & npm.cmd run build; if ($LASTEXITCODE -ne 0) { throw 'Frontend build failed' }
    } finally { Pop-Location }
    & $Maven -B clean verify
    if ($LASTEXITCODE -ne 0) { throw 'Maven clean verify failed' }
    & python -m unittest discover -s experiments/tests -v
    if ($LASTEXITCODE -ne 0) { throw 'Experiment tests failed' }
    Write-Output "Verification passed. MySQL enabled: $WithMysql. Browser E2E and real-fare evidence are separate checks."
} finally {
    $env:JAVA_HOME = $previousJavaHome
    $env:PATH = $previousPath
    $env:MYSQL_TESTS = $previousMysqlTests
    $env:MYSQL_TEST_URL = $previousMysqlUrl
    Set-Location -LiteralPath $previousLocation
}
