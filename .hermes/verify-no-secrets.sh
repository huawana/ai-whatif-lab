#!/usr/bin/env bash
# ============================================================================
# 提交前密钥扫描 —— 保证「真实密钥永不进 Git」
#
#   bash .hermes/verify-no-secrets.sh      退出码 0 = 干净；非 0 = 有泄露，禁止提交
#
# 三层检查：
#   ① 用 .hermes/ai.env 里的**真实值**反扫整个仓库（本地才有，别人机器上自动跳过）
#   ② 通用密钥模式扫（sk-xxx / AKIA / 私钥头 / ghp_ 等），不依赖本地有没有 ai.env
#   ③ 关键文件必须处于「被忽略」状态；若被 git 跟踪就直接报错
#
# 为什么要有这个脚本：README 里写"不要把 key 提交"是没用的，
# 只有让检查可执行、且每次提交都跑，才是真的防线。
# ============================================================================
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1
FAIL=0
EX="--exclude-dir=.git --exclude-dir=target --exclude-dir=tmp --exclude-dir=logs"

echo "== ① 用真实值反扫（本地）=="
if [ -f .hermes/ai.env ]; then
  while IFS= read -r line; do
    name=$(printf '%s' "$line" | sed -n 's/^[[:space:]]*export[[:space:]]*\([A-Za-z_]*\)=.*/\1/p')
    [ -n "$name" ] || continue
    case "$name" in *KEY*|*TOKEN*|*SECRET*|*PASSWORD*) ;; *) continue ;; esac
    val=$(printf '%s' "$line" | sed -n 's/^[[:space:]]*export[[:space:]]*[A-Za-z_]*=[[:space:]]*//p' | tr -d '"'"'"'\r')
    [ ${#val} -ge 8 ] || continue
    hits=$(grep -rIl $EX -F "$val" . 2>/dev/null | grep -v '^\./\.hermes/ai\.env$')
    if [ -n "$hits" ]; then
      echo "  ✗ $name 的真实值出现在："
      echo "$hits" | sed 's/^/      /'
      FAIL=1
    fi
  done < .hermes/ai.env
  [ $FAIL -eq 0 ] && echo "  ✓ 除 .hermes/ai.env 本身外，没有任何文件含真实值"
else
  echo "  - 跳过（本机没有 .hermes/ai.env）"
fi

echo "== ② 通用密钥模式扫 =="
PAT='sk-[A-Za-z0-9]{24,}|AKIA[0-9A-Z]{16}|-----BEGIN [A-Z ]*PRIVATE KEY-----|ghp_[A-Za-z0-9]{30,}|xox[baprs]-[A-Za-z0-9-]{20,}'
RAW=$(grep -rInE $EX "$PAT" . 2>/dev/null | grep -v 'ai.env.example')
REAL=$(printf '%s\n' "$RAW" | grep -vEi 'mock|dummy|fake|example|test|xxx|your-key|placeholder' | grep -v '^$')
if [ -n "$REAL" ]; then
  echo "  ✗ 疑似真实密钥："; printf '%s\n' "$REAL" | head -8 | sed 's/^/      /'; FAIL=1
else
  N=$(printf '%s\n' "$RAW" | grep -vc '^$' || true)
  echo "  ✓ 无真实密钥模式（命中 $N 处，全部是 mock/example/placeholder 之类的假值）"
fi

echo "== ③ 关键文件必须是「被忽略」状态 =="
for f in .hermes/ai.env .env; do
  [ -e "$f" ] || continue
  if git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    if git ls-files --error-unmatch "$f" >/dev/null 2>&1; then
      echo "  ✗ $f 已被 git 跟踪 —— 必须 git rm --cached 并从历史里清掉"; FAIL=1
    else
      echo "  ✓ $f 存在但未被跟踪（被 .gitignore 挡住）"
    fi
  else
    echo "  - 还不是 git 仓库，跳过"
  fi
done

echo
[ $FAIL -eq 0 ] && echo "结果：干净，可以提交" || echo "结果：发现泄露风险，禁止提交"
exit $FAIL
