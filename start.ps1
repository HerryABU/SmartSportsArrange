param([int]$Port = -1, [string]$Host = "")

try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch {}

$root = $PSScriptRoot
$jar = Join-Path $root "sports-2.8.5.jar"

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

# 仅当端口被显式解析（-Port 参数 / app-config.json）时才作为命令行注入；
# 兜底 8080 交给 jar 内部优先级链（OS 环境变量 > .env SERVER_PORT > 默认），否则会压住 .env。
$extra = @()
if ($Port -gt 0) { $extra += @("--app.port=$Port") }
if ($Host -ne "") { $extra += @("--app.host=$Host") }

if ($Port -gt 0) {
  Write-Host "=== Starting on port $Port ($(if ($Host -ne '') { "host $Host" } else { 'all interfaces' })) ===" -ForegroundColor Green
  Write-Host "    Open: http://localhost:$Port" -ForegroundColor Cyan
} else {
  Write-Host "=== Port: following OS env / .env SERVER_PORT (default 8080) ===" -ForegroundColor Green
}
Write-Host ""
java -jar $jar @extra
