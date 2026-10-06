# 运动会智能编排系统 v2.8.7 —— 外置模型版启动程序（自带 JDK，模型在 jar 外）
# 用法：
#   .\start.ps1                        默认端口（跟随 data/app-config.json / .env SERVER_PORT，兜底 8080）
#   .\start.ps1 -Port 8899             指定端口
#   .\start.ps1 -Port 8899 -Host ::    指定绑定地址（-Host 是 -BindHost 的别名）
#   .\start.ps1 -ModelDir D:\ai-models 指定模型目录（默认用本目录下的 models）
#
# 与嵌入式版的唯一区别：模型不进 jar，而是从 jar 旁边的 models 目录读取
# （--sports.schedule.ai.model-dir）。好处是换模型无需重新打包，把新 onnx 覆盖进 models 即可。
param([int]$Port = -1, [Alias("Host")][string]$BindHost = "", [string]$Xmx = "", [string]$ModelDir = "")

try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch {}

$root = $PSScriptRoot
$jar = Join-Path $root "sports-2.8.7.jar"
if (-not (Test-Path $jar)) {
  Write-Host "[ERROR] 未找到 sports-2.8.7.jar，请与 start.ps1 放在同一目录" -ForegroundColor Red
  exit 1
}

# 模型目录：-ModelDir 参数 > 本目录下的 models
$md = if ($ModelDir -ne "") { $ModelDir } else { Join-Path $root "models" }
if (-not (Test-Path $md)) {
  Write-Host "[ERROR] 未找到模型目录：$md" -ForegroundColor Red
  Write-Host "        外置模型版必须把 models 目录放在 jar 旁边（或显式指定 -ModelDir）" -ForegroundColor Red
  exit 1
}
$onnx = @(Get-ChildItem -Path $md -Filter *.onnx -File)
if ($onnx.Count -eq 0) {
  Write-Host "[ERROR] 模型目录里没有 .onnx 文件：$md" -ForegroundColor Red
  exit 1
}
# 缺模型不会让程序启动失败，而是让对应能力**静默**退化为规则 —— 所以这里必须显式提示
if ($onnx.Count -lt 16) {
  Write-Host "[WARN] 模型目录只有 $($onnx.Count) 个 onnx（应为 16 个）：缺失的模型会让对应 AI 能力静默退化为规则模式" -ForegroundColor Yellow
}

# JDK 查找顺序：本目录 → 上级目录 → JAVA_HOME → PATH（随包便携 JDK 优先，实现免安装运行）
$cands = @(
  (Join-Path $root "jdk-21.0.12.1+1\bin\java.exe"),
  (Join-Path (Split-Path $root -Parent) "jdk-21.0.12.1+1\bin\java.exe")
)
if ($env:JAVA_HOME) { $cands += (Join-Path $env:JAVA_HOME "bin\java.exe") }
$java = $cands | Where-Object { $_ -and (Test-Path $_) } | Select-Object -First 1
if (-not $java) {
  Write-Host "[WARN] 未找到随包 JDK，改用系统 PATH 中的 java（需自行安装 Java 21 或更高）" -ForegroundColor Yellow
  $java = "java"
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

$extra = @("--sports.schedule.ai.model-dir=$md")
if ($Port -gt 0) { $extra += @("--app.port=$Port") }
if ($BindHost -ne "") { $extra += @("--app.host=$BindHost") }
$jvm = @()
if ($Xmx -ne "") { $jvm += @("-Xmx$Xmx") }
$bindTip = if ($BindHost -ne "") { "    绑定: $BindHost" } else { "" }

Write-Host "=== 运动会智能编排系统 v2.8.7（外置模型版：模型从 jar 外的 models 目录读取）===" -ForegroundColor Green
Write-Host "    Java : $java" -ForegroundColor Cyan
Write-Host "    模型 : $md（$($onnx.Count) 个 onnx）" -ForegroundColor Cyan
Write-Host "    端口 : $Port$bindTip" -ForegroundColor Cyan
Write-Host "    访问 : http://localhost:$Port" -ForegroundColor Cyan
Write-Host "    停止 : 在本窗口按 Ctrl+C" -ForegroundColor DarkGray
Write-Host ""

# 控制台编码：启动器已把控制台切到 UTF-8，JVM 默认仍用系统字符集（中文 Windows = GBK）⇒ 中文全乱码。
# ⚠️ 必须写成「带引号的数组」：不加引号时 PowerShell 会在 '.' 处把 -Dfoo.bar=baz 拆成两个参数，
#    JVM 于是收到 `.bar=baz` 并当成主类名 → 启动失败（ClassNotFoundException: /bar=baz）。
$enc = @("-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
& $java @enc @jvm -jar $jar @extra
