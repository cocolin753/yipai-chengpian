#!/usr/bin/env bash
# 下载语音识别模型到 ios/Models（模型太大，不放进 git）
set -euo pipefail
cd "$(dirname "$0")/../Models"
MODEL=sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17
BASE=https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models
if [ ! -f model.int8.onnx ]; then
  curl -fsSL "$BASE/$MODEL.tar.bz2" | tar xj "$MODEL/model.int8.onnx" "$MODEL/tokens.txt"
  mv "$MODEL"/* . && rmdir "$MODEL"
fi
[ -f silero_vad.onnx ] || curl -fsSL -o silero_vad.onnx "$BASE/silero_vad.onnx"
ls -la
