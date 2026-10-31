#!/usr/bin/env bash
# 在电脑上下载最新的 iOS 安装包，再用 iloader 的「导入 IPA」安装到手机
set -euo pipefail
OUT="${1:-$HOME/YiPai.ipa}"
curl -fL -o "$OUT" https://github.com/cocolin753/yipai-chengpian/releases/latest/download/YiPai.ipa
echo "已下载到 $OUT"
