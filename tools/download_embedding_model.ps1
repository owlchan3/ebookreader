# 下载 bge-small-zh-v1.5 量化 ONNX 模型 + 词表到 app 资源目录。
# 来源：Xenova/bge-small-zh-v1.5（https://huggingface.co/Xenova/bge-small-zh-v1.5）
# 中国大陆默认走 hf-mirror.com 镜像；如需切换镜像，设置 $env:HF_ENDPOINT 即可。
#
# 用法：powershell -ExecutionPolicy Bypass -File tools\download_embedding_model.ps1

$ErrorActionPreference = "Stop"

$base = $env:HF_ENDPOINT
if (-not $base) { $base = "https://hf-mirror.com" }

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$outDir = Join-Path $scriptDir "..\app\src\main\assets\ml"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

$modelUrl = "$base/Xenova/bge-small-zh-v1.5/resolve/main/onnx/model_quantized.onnx"
$vocabUrl = "$base/Xenova/bge-small-zh-v1.5/resolve/main/vocab.txt"

Write-Host "下载模型 -> $outDir\bge-small-zh.onnx"
Invoke-WebRequest -Uri $modelUrl -OutFile (Join-Path $outDir "bge-small-zh.onnx")

Write-Host "下载词表 -> $outDir\vocab.txt"
Invoke-WebRequest -Uri $vocabUrl -OutFile (Join-Path $outDir "vocab.txt")

Write-Host "完成。期望大小：bge-small-zh.onnx ≈ 24,010,842 字节；vocab.txt ≈ 109,540 字节。"
