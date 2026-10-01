# 训练 + 导出 ONNX
# 用法：在 sports-ai/ 目录下执行  .\scripts\train.ps1
$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
$Py = "$Root\venv\Scripts\python.exe"

if (-not (Test-Path $Py)) {
    Write-Error "虚拟环境不存在，请先执行 .\scripts\setup_venv.ps1"
}

Push-Location $Root
try {
    Write-Host "==> 训练算法选择器（硬解 vs 取消）..."
    & $Py -m sports_ai.train_selector --samples 4000 --epochs 30

    Write-Host "==> 训练冲突簇 GNN（着色优先级）..."
    & $Py -m sports_ai.train_gnn --samples 1500 --epochs 20

    Write-Host "==> 导出 ONNX 并自检..."
    & $Py -m sports_ai.export_onnx --verify
}
finally {
    Pop-Location
}
