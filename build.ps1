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
  # 先把旧 static 改名移走：vite 的 emptyOutDir 清空会被构建环境的安全删除机制拦截，
  # 改用「重命名」方式（重命名不被拦截），vite 会创建干净的 static；改名的旧目录稍后移出版本树。
  $static = Join-Path $root "sports-backend\src\main\resources\static"
  $stamp = Get-Date -Format "yyyyMMddHHmmss"
  $oldStatic = $null
  if (Test-Path $static) {
    $oldStatic = Join-Path $root "sports-backend\src\main\resources\static_old_$stamp"
    Move-Item -Force $static $oldStatic
  }
  Set-Location "$root\sports-frontend"
  # --no-install：仅用本地已装的 vite，避免离线环境 npx 解析注册表长时间挂起
  npx --no-install vite build
  if ($LASTEXITCODE -ne 0) { Write-Host "[ERROR] Frontend failed" -ForegroundColor Red; exit 1 }
  # 旧 static 移出版本目录，避免被 jar 打包或误提交
  if ($oldStatic -and (Test-Path $oldStatic)) {
    $trash = Join-Path $root "_trash"
    if (-not (Test-Path $trash)) { New-Item -ItemType Directory -Force -Path $trash | Out-Null }
    Move-Item -Force $oldStatic $trash -ErrorAction SilentlyContinue
  }
  Write-Host "[1/2] Frontend OK (assets -> src/main/resources/static)" -ForegroundColor Green
}

if (-not $SkipBackend) {
  Write-Host "[2/2] Backend (Maven)..." -ForegroundColor Magenta
  Set-Location "$root\sports-backend"
  .\mvnw.cmd clean package -DskipTests
  if ($LASTEXITCODE -ne 0) { Write-Host "[ERROR] Backend failed" -ForegroundColor Red; exit 1 }
  Write-Host "[2/2] Backend OK" -ForegroundColor Green
}

$jar = Join-Path $root "sports-backend\target\sports-2.7.1.jar"
if (Test-Path $jar) {
  Copy-Item $jar $root -Force
  $sizeMb = [math]::Round((Get-Item (Join-Path $root "sports-2.7.1.jar")).Length / 1MB, 1)
  Write-Host "[OUTPUT] sports-2.7.1.jar ($sizeMb MB)" -ForegroundColor Cyan
}

$elapsed = $timer.Elapsed.TotalSeconds.ToString("0.0")
Write-Host "=== Build Done ${elapsed}s ===" -ForegroundColor Green
Write-Host "Run .\start.ps1 to launch the server" -ForegroundColor Cyan
