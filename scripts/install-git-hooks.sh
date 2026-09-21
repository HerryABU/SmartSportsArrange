#!/bin/sh
# 一次性安装 git hooks：把 .githooks/pre-commit 复制到 .git/hooks 并赋予可执行权限。
# 之后每次 git commit 都会自动触发提交前单测门禁。
ROOT="$(git rev-parse --show-toplevel)"
SRC="$ROOT/.githooks/pre-commit"
DST="$ROOT/.git/hooks/pre-commit"

if [ ! -f "$SRC" ]; then
  echo "找不到 $SRC" >&2
  exit 1
fi

cp "$SRC" "$DST"
chmod +x "$DST"
echo "已安装 pre-commit 钩子 → $DST"
echo "提交时将自动运行关键路径单测；紧急跳过用 git commit --no-verify"
