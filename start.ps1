param([int]$Port = 8080)

try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch {}

$root = $PSScriptRoot
$jar = Join-Path $root "sports-1.0.0.jar"

if (-not (Test-Path $jar)) {
  Write-Host "[ERROR] JAR not found, run .\build.ps1 first" -ForegroundColor Red
  exit 1
}

Write-Host "=== Starting on port $Port ===" -ForegroundColor Green
Write-Host "    Open: http://localhost:$Port" -ForegroundColor Cyan
Write-Host ""
java -jar $jar --server.port=$Port
