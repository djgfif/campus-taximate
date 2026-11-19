# 이 프로젝트 전용 MySQL을 관리자 권한 없이 3307 포트로 띄운다. 기존 MySQL 서비스와 데이터는 건드리지 않는다.
# 처음 한 번은 데이터 폴더를 만들고 taximate DB와 사용자(비밀번호 taximate, 이 컴퓨터에서만 접속)를 만든다.
param([string]$Data = 'C:\CodeStorage\mysql-taximate\data')
$bin = 'C:\Program Files\MySQL\MySQL Server 8.0\bin'
$base = Split-Path $bin
if (-not (Test-Path $Data)) {
  New-Item -ItemType Directory -Force (Split-Path $Data) | Out-Null
  & "$bin\mysqld.exe" --initialize-insecure --basedir="$base" --datadir="$Data" --console
}
Start-Process -FilePath "$bin\mysqld.exe" -WindowStyle Hidden -ArgumentList @(
  "--basedir=`"$base`"", "--datadir=`"$Data`"", '--port=3307', '--bind-address=127.0.0.1', '--mysqlx=OFF',
  '--character-set-server=utf8mb4', '--collation-server=utf8mb4_unicode_ci')
for ($i = 0; $i -lt 30; $i++) {
  & "$bin\mysql.exe" -uroot --port=3307 --host=127.0.0.1 -e 'select 1' 2>$null | Out-Null
  if ($LASTEXITCODE -eq 0) { break }
  Start-Sleep -Seconds 1
}
& "$bin\mysql.exe" -uroot --port=3307 --host=127.0.0.1 -e @"
CREATE DATABASE IF NOT EXISTS taximate CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS 'taximate'@'localhost' IDENTIFIED BY 'taximate';
CREATE USER IF NOT EXISTS 'taximate'@'127.0.0.1' IDENTIFIED BY 'taximate';
GRANT ALL ON taximate.* TO 'taximate'@'localhost';
GRANT ALL ON taximate.* TO 'taximate'@'127.0.0.1';
"@
Write-Output 'MySQL 3307 준비 완료'
