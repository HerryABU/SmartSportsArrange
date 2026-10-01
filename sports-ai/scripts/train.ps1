# 训练 + 导出 ONNX（全 AI 核心：选择器 / GNN / GAN / 多步预测 / 自步学习）
# 用法：在 sports-ai/ 目录下执行  .\scripts\train.ps1
$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
$Py = "$Root\venv\Scripts\python.exe"

if (-not (Test-Path $Py)) {
    Write-Error "虚拟环境不存在，请先执行 .\scripts\setup_venv.ps1"
}

Push-Location $Root
try {
    Write-Host "==> ① 算法选择器（硬解 vs 取消）..."
    & $Py -m sports_ai.train_selector --samples 4000 --epochs 30

    Write-Host "==> ② 冲突簇 GNN（着色优先级）..."
    & $Py -m sports_ai.train_gnn --samples 1500 --epochs 20

    Write-Host "==> ③ GAN 对抗训练（生成器 + 判别器，minimax）..."
    & $Py -m sports_ai.generative.train_gan --iters 1500 --batch 16

    Write-Host "==> ③b 对抗精修器（把推理时自对抗精修蒸馏成一次前向）..."
    & $Py -m sports_ai.generative.train_refiner --iters 2500

    Write-Host "==> ④ 多步预测（Direct / Recursive / MIMO）..."
    & $Py -m sports_ai.forecast.train_forecast --samples 2000 --epochs 20

    Write-Host "==> ⑤ 自步学习（课程训练 + 自改进）..."
    & $Py -m sports_ai.curriculum.train_self_paced --pool 2000 --epochs 20

    Write-Host "==> ⑥ 道次编排 AI（运动员派遣顺序，Learning-to-Rank）..."
    & $Py -m sports_ai.lane_advisor --iters 1500 --batch 32

    Write-Host "==> ⑦ 导出全部 ONNX 并自检..."
    & $Py -m sports_ai.export_onnx --verify
    & $Py -m sports_ai.generative.export_gan --verify
    & $Py -m sports_ai.forecast.export_forecast --verify
    & $Py -m sports_ai.lane_advisor --export-only --verify

    Write-Host "==> ⑧ 验证推理时自对抗精修（单次生成 vs 对抗精修）..."
    & $Py -m sports_ai.generative.validate_refine
}
finally {
    Pop-Location
}
