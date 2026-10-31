#!/usr/bin/env bash
# 下载语音识别模型和 sherpa-onnx 库（体积太大，不放进 git）
set -euo pipefail
cd "$(dirname "$0")/../app"
mkdir -p libs src/main/assets/models
[ -f libs/sherpa-onnx-2.0.1.aar ] || curl -fsSL -o libs/sherpa-onnx-2.0.1.aar \
  https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.1/sherpa-onnx-2.0.1.aar
cd src/main/assets/models
MODEL=sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17
BASE=https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models
if [ ! -f model.int8.onnx ]; then
  curl -fsSL "$BASE/$MODEL.tar.bz2" | tar xj "$MODEL/model.int8.onnx" "$MODEL/tokens.txt"
  mv "$MODEL"/* . && rmdir "$MODEL"
fi
[ -f silero_vad.onnx ] || curl -fsSL -o silero_vad.onnx "$BASE/silero_vad.onnx"
