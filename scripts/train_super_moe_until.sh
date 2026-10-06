#!/bin/bash
# 主 MoE 扩容版「训到达标为止」的自动续训器。
#
# 为什么需要它：单元训练脚本每次只跑一段固定轮数，且**每段都会重启一个
# 3-epoch 的余弦 LR**（相当于周期性 warm restart）。要靠人工"看一眼再决定要不要续"
# 会浪费大量空等时间，而这一轮每段就 ~1.5 小时。
#
# 达标判据：val_loss < TARGET。TARGET 取 **1.45** —— 旧版 19 专家 6 轮的最好成绩是
# 1.4994，所以 1.45 表示「确实超过旧版」而不是打平。达到即停，避免无谓烧机。
set -u
cd "$(dirname "$0")/../sports-ai" || exit 1

export PYTHONPATH=.
export OMP_NUM_THREADS=8
PY="C:/Users/QBZ95/AppData/Local/Programs/Python/Python310/python.exe"
STATS="models/super_moe_stats.json"
TARGET="${TARGET:-1.45}"
ROUND_EPOCHS="${ROUND_EPOCHS:-3}"
MAX_ROUNDS="${MAX_ROUNDS:-8}"

echo "[loop] 目标 val_loss < $TARGET ｜ 每段 $ROUND_EPOCHS 轮 ｜ 上限 $MAX_ROUNDS 段"
echo "[loop] 起始 $(date '+%m-%d %H:%M:%S')"

for r in $(seq 1 "$MAX_ROUNDS"); do
  BEFORE=$("$PY" -c "import json;print(json.load(open('$STATS',encoding='utf-8'))['val_loss'])" 2>/dev/null || echo 999)
  echo ""
  echo "########## ROUND $r ##########  进入时 best=$BEFORE  $(date '+%H:%M:%S')"
  "$PY" -u -m sports_ai.train_super_moe --samples 700 --epochs "$ROUND_EPOCHS" \
      --batch 4 --expert-depth 6 --n-nested 4 --nest-experts 8 \
      --ensemble --device cpu --resume 2>&1 | grep -E "^epoch|^best |^\[save\]|^\[stop\]|已落盘|Error|Traceback"
  RC=${PIPESTATUS[0]}
  AFTER=$("$PY" -c "import json;print(json.load(open('$STATS',encoding='utf-8'))['val_loss'])" 2>/dev/null || echo 999)
  echo ">>> ROUND $r 结束 rc=$RC  best: $BEFORE → $AFTER  $(date '+%H:%M:%S')"
  if [ "$RC" != "0" ]; then echo "[loop] 训练异常退出，中止"; exit "$RC"; fi
  OK=$("$PY" -c "print(1 if $AFTER < $TARGET else 0)")
  if [ "$OK" = "1" ]; then
    echo "[loop] ✅ 达标：val=$AFTER < $TARGET（共 $r 段）"
    exit 0
  fi
  # 若一段跑完毫无改善，说明已经收敛到平台 —— 再续也只是烧机，如实报告后停。
  IMP=$("$PY" -c "print(1 if $AFTER < $BEFORE - 0.005 else 0)")
  if [ "$IMP" = "0" ]; then
    echo "[loop] ⚠️ 本段无实质改善（$BEFORE → $AFTER），判定已达平台期，停止续训"
    exit 3
  fi
done
echo "[loop] ⚠️ 已达段数上限 $MAX_ROUNDS，best=$("$PY" -c "import json;print(json.load(open('$STATS',encoding='utf-8'))['val_loss'])")"
exit 4
