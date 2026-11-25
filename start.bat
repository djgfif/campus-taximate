@echo off
setlocal
chcp 65001 >nul
cd /d "%~dp0"
title Campus TaxiMate - One Click Start

rem Place this file next to compose.yaml / compose.yml / docker-compose.yml.
if not exist "compose.yaml" if not exist "compose.yml" if not exist "docker-compose.yml" if not exist "docker-compose.yaml" (
    echo [ERROR] Docker Compose file was not found in:
    echo %CD%
    echo Place this BAT file in the campus-taximate project root.
    goto :error
)

where docker >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Docker CLI was not found. Install or start Docker Desktop.
    goto :error
)

docker info >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Docker Engine is not running. Start Docker Desktop and retry.
    goto :error
)

echo [1/3] Building and starting containers...
docker compose up -d --build
if errorlevel 1 goto :error

echo.
echo [2/3] Waiting for the website at http://localhost:8080/ ...
powershell -NoProfile -ExecutionPolicy Bypass -Command "$u='http://localhost:8080/'; for($i=0;$i -lt 60;$i++){try{$r=Invoke-WebRequest -UseBasicParsing -Uri $u -TimeoutSec 3; if($r.StatusCode -ge 200 -and $r.StatusCode -lt 400){exit 0}}catch{}; Start-Sleep -Seconds 2}; exit 1"
if errorlevel 1 (
    echo [WARNING] The application did not respond at localhost:8080.
    echo Check whether compose.yaml publishes port 8080 to the host.
    docker compose ps
    echo.
    echo To check errors: docker compose logs --tail=100 app
    goto :error
)

echo [3/3] Opening the website...
start "" "http://localhost:8080/"
echo.
echo [SUCCESS] Campus TaxiMate is running.
echo To view logs: docker compose logs -f app
echo To stop: docker compose down
pause
exit /b 0

:error
echo.
echo [FAILED] Startup or connectivity check failed. See messages above.
pause
exit /b 1
