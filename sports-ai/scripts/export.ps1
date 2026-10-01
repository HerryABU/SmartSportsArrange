# 仅导出 ONNX（前提：已训练出 models/selector.pt + gnn.pt + selector_stats.json）
# 用法：在 sports-ai/ 目录下执行  .\scripts\export.ps1
$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
$Py = "$Root\venv\Scripts\python.exe"
Push-Location $Root
try {
    & $Py -m sports_ai.export_onnx --verify
}
finally {
    Pop-Location
}
