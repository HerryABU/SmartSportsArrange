param([int]$Port = -1)

try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch {}

$root = $PSScriptRoot
$jar = Join-Path $root "sports-1.0.0.jar"

if (-not (Test-Path $jar)) {
  Write-Host "[ERROR] JAR not found, run .\build.ps1 first" -ForegroundColor Red
  exit 1
}

# 固定工作目录为脚本所在目录（data/app-config.json、sports_meet.db 均相对此目录）
Set-Location $root

# 端口解析：-Port 参数 > data/app-config.json > 默认 8080
if ($Port -lt 0) {
  $cfg = Join-Path $root "data\app-config.json"
  if (Test-Path $cfg) {
    try {
      $c = Get-Content $cfg -Raw | ConvertFrom-Json
      if ($null -ne $c.port -and [int]$c.port -gt 0) { $Port = [int]$c.port }
    } catch {}
  }
}
if ($Port -lt 0) { $Port = 8080 }

Write-Host "=== Starting on port $Port ===" -ForegroundColor Green
Write-Host "    Open: http://localhost:$Port" -ForegroundColor Cyan
Write-Host ""
java -jar $jar --server.port=$Port
