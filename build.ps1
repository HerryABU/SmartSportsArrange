param([switch]$SkipFrontend, [switch]$SkipBackend)
$ErrorActionPreference = "Continue"
$root = $PSScriptRoot

# Auto-detect terminal encoding
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch {}
$env:JAVA_TOOL_OPTIONS = "-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8"

Write-Host "=== Sports Meet Build Start $(Get-Date -Format HH:mm:ss) ===" -ForegroundColor Green
$timer = [System.Diagnostics.Stopwatch]::StartNew()

if (-not $SkipFrontend) {
  Write-Host "[1/2] Frontend (Vite)..." -ForegroundColor Magenta
  Set-Location "$root\sports-frontend"
  npx vite build
  if ($LASTEXITCODE -ne 0) { Write-Host "[ERROR] Frontend failed" -ForegroundColor Red; exit 1 }
  # 同步前端产物到后端静态资源目录（static 已 gitignore，构建时生成，保证 jar 打包包含前端）
  $dist = Join-Path $root "sports-frontend\dist"
  $static = Join-Path $root "sports-backend\src\main\resources\static"
  if (Test-Path $dist) {
    if (Test-Path $static) { Remove-Item -Recurse -Force $static }
    Copy-Item -Recurse $dist $static
    Write-Host "[1/2] Frontend assets synced to src/main/resources/static" -ForegroundColor Green
  }
  Write-Host "[1/2] Frontend OK" -ForegroundColor Green
}

if (-not $SkipBackend) {
  Write-Host "[2/2] Backend (Maven)..." -ForegroundColor Magenta
  Set-Location "$root\sports-backend"
  .\mvnw.cmd clean package -DskipTests
  if ($LASTEXITCODE -ne 0) { Write-Host "[ERROR] Backend failed" -ForegroundColor Red; exit 1 }
  Write-Host "[2/2] Backend OK" -ForegroundColor Green
}

$jar = Join-Path $root "sports-backend\target\sports-1.0.0.jar"
if (Test-Path $jar) {
  Copy-Item $jar $root -Force
  $sizeMb = [math]::Round((Get-Item (Join-Path $root "sports-1.0.0.jar")).Length / 1MB, 1)
  Write-Host "[OUTPUT] sports-1.0.0.jar ($sizeMb MB)" -ForegroundColor Cyan
}

$elapsed = $timer.Elapsed.TotalSeconds.ToString("0.0")
Write-Host "=== Build Done ${elapsed}s ===" -ForegroundColor Green
Write-Host "Run .\start.ps1 to launch the server" -ForegroundColor Cyan
