# 创建 Python 3.12 虚拟环境并安装训练依赖
# 用法：在 sports-ai/ 目录下执行  .\scripts\setup_venv.ps1
$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot

$Py312 = "C:\Users\QBZ95\AppData\Local\Programs\Python\Python312\python.exe"
if (-not (Test-Path $Py312)) {
    Write-Error "未找到 Python 3.12：$Py312"
}

Write-Host "==> 创建虚拟环境 venv（Python 3.12）..."
& $Py312 -m venv "$Root\venv"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$VenvPy = "$Root\venv\Scripts\python.exe"
Write-Host "==> 升级 pip..."
& $VenvPy -m pip install --upgrade pip

Write-Host "==> 安装依赖（torch CPU / onnx / onnxruntime ...）..."
& $VenvPy -m pip install -r "$Root\requirements.txt"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host "==> 完成。激活方式： .\venv\Scripts\Activate.ps1"
