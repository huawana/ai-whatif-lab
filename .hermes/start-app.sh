#!/usr/bin/env bash
# 启动应用（后台）并等待 Spring Boot 就绪。
#
# 【铁律】必须等到日志出现 "Started ...Application" 才算启动成功 ——
# Tomcat 端口起来 ≠ 上下文刷新完成 ≠ 接口可用。脚本化启动时用端口探测会得到假成功。
#
# 【MSYS 坑 · 实测踩到】`nohup java ... &` 取到的 `$!` 是 **MSYS 自己的 pid**，不是 Windows pid。
# 用它去 `taskkill /PID` 打的是另一个命名空间 —— 结果是"停止成功"的假象 + 进程继续跑。
# 所以这里在应用就绪后，改为从监听端口反查真正的 Windows pid 并写入 pid 文件，
# 停脚本只认这个数字（见 stop-app.sh）。
#
# 用法: .hermes/start-app.sh [额外的 --key=value ...]
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

# 本机 AI 供应商配置（.hermes/ai.env，含密钥，不进 git）。
# 存在就自动加载 —— 这样换供应商只改那个文件，启动命令永远是同一行。
#
# 【为什么先快照再恢复】朴素写法 `set -a; . ai.env; set +a` 会**无条件覆盖**命令行已传的值，
# 于是 `WHATIF_AI_MODEL=xxx bash start-app.sh` 这种临时覆盖会静默失效 ——
# 而 ai.env 的注释里恰好承诺"命令行可临时覆盖"。文件是默认值，环境变量是显式意图，
# 显式意图优先级更高，所以这里把命令行已设置的项在 source 之后放回去。
# 【例外】WHATIF_NO_KEY=1 表示"假装没有凭证"（降级验证要用）——
# 这时必须完全不加载 ai.env，否则文件里的 key 会把该场景抵消掉，
# 表现为"无 key 时 aiAvailable 竟然是 true"（整套验收里被抓到过）。
if [ -f .hermes/ai.env ] && [ "${WHATIF_NO_KEY:-}" != "1" ]; then
  _KEEP_PROVIDER="${WHATIF_AI_PROVIDER:-}"
  _KEEP_BASEURL="${WHATIF_AI_BASE_URL:-}"
  _KEEP_MODEL="${WHATIF_AI_MODEL:-}"
  _KEEP_KEY="${WHATIF_AI_API_KEY:-}"
  set -a
  # shellcheck disable=SC1091
  . .hermes/ai.env
  set +a
  [ -n "$_KEEP_PROVIDER" ] && export WHATIF_AI_PROVIDER="$_KEEP_PROVIDER"
  [ -n "$_KEEP_BASEURL" ] && export WHATIF_AI_BASE_URL="$_KEEP_BASEURL"
  [ -n "$_KEEP_MODEL" ] && export WHATIF_AI_MODEL="$_KEEP_MODEL"
  [ -n "$_KEEP_KEY" ] && export WHATIF_AI_API_KEY="$_KEEP_KEY"
  unset _KEEP_PROVIDER _KEEP_BASEURL _KEEP_MODEL _KEEP_KEY
fi

JAR=target/ai-whatif-lab-1.0.0.jar
LOG=.hermes/logs/app.log
PIDFILE=.hermes/app.pid
mkdir -p .hermes/logs

# ---- jar 完整性自检（血泪教训）----
# Windows 上如果**应用还在运行时**执行 mvn package，jar 被进程锁住 → 产物损坏，
# 表现为启动时 java 立刻退出、日志只写一句"没有主清单属性"，看不出真正原因。
# 所以这里先验一次：没有 Main-Class 就明确告诉人"先停应用再打包"。
if [ ! -f "$JAR" ]; then
  echo "jar 不存在：$JAR —— 先执行 bash .hermes/mvn.sh -B -q -DskipTests package" >&2
  exit 4
fi
if ! unzip -p "$JAR" META-INF/MANIFEST.MF 2>/dev/null | grep -q "Main-Class"; then
  echo "jar 已损坏（MANIFEST 里没有 Main-Class）：$JAR" >&2
  echo "常见原因：打包时应用还在运行，Windows 锁住了 jar 文件。" >&2
  echo "正确顺序：bash .hermes/stop-app.sh && bash .hermes/mvn.sh -B -q -DskipTests package && bash .hermes/start-app.sh" >&2
  exit 4
fi

# 端口：命令行 --server.port 优先，其次 SERVER_PORT，最后默认 8088
PORT="$(printf '%s\n' "$@" | grep -o -- '--server.port=[0-9]*' | head -1 | cut -d= -f2)"
PORT="${PORT:-${SERVER_PORT:-8088}}"

if [ ! -f "$JAR" ]; then
  echo "缺少 $JAR，请先: bash .hermes/mvn.sh -DskipTests package"
  exit 3
fi

# 启动前把"实际生效的 AI 供应商配置"打印一行（key 只报有没有，不报内容）——
# 换供应商时最怕"以为改了其实没生效"，这一行就是用来一眼确认的。
if [ -n "${WHATIF_AI_PROVIDER:-}" ]; then
  echo "AI 配置：provider=${WHATIF_AI_PROVIDER} model=${WHATIF_AI_MODEL:-未指定} baseUrl=${WHATIF_AI_BASE_URL:-未指定} key=$([ -n "${WHATIF_AI_API_KEY:-}" ] && echo 已配置 || echo 未配置)"
fi

# AI Key：优先用标准的 AI_DASHSCOPE_API_KEY，否则从本机已有的 ALIQWEN-API 取。
# 绝不把 key 写进仓库；配置里用的是 ${AI_DASHSCOPE_API_KEY:${ALIQWEN-API:}} 占位符。
#
# 【无凭证路径必须能被复现】设 WHATIF_NO_KEY=1 可强制跳过取 key，用来回归测试
# 「没配 Key 时应用能不能照常启动」—— 这是本项目最硬的一条底线测试，
# 不能只靠「手动删环境变量试试」，必须有一个固定的、可重复的入口。
if [ -z "${WHATIF_NO_KEY:-}" ]; then
  if [ -z "${AI_DASHSCOPE_API_KEY:-}" ]; then
    ALIQWEN="$(printenv 'ALIQWEN-API' 2>/dev/null || true)"
    if [ -n "$ALIQWEN" ]; then
      AI_DASHSCOPE_API_KEY="$ALIQWEN"
    fi
  fi
else
  AI_DASHSCOPE_API_KEY=""
  echo "WHATIF_NO_KEY=1：已强制清空 AI Key（回归测试无凭证启动路径）"
fi
export AI_DASHSCOPE_API_KEY
export WHATIF_AI_ENABLED="${WHATIF_AI_ENABLED:-true}"

# 先确认端口没被占用：否则新实例会启动失败，而日志里的报错很容易被误读成配置问题
if netstat -ano | grep -qE "TCP.*:$PORT .*LISTENING"; then
  echo "端口 $PORT 已被占用，请先执行 .hermes/stop-app.sh（或换 --server.port=xxxx）"
  exit 4
fi

: > "$LOG"
nohup java -jar "$JAR" "$@" > "$LOG" 2>&1 &
LAUNCH_PID=$!

resolve_win_pid() {
  netstat -ano | grep -E "TCP.*:$PORT .*LISTENING" | awk '{print $NF}' | head -1
}

echo "已启动 java 进程（MSYS pid=$LAUNCH_PID），端口=$PORT，日志=$LOG"
echo "（AI_ENABLED=$WHATIF_AI_ENABLED, key=$([ -n "${AI_DASHSCOPE_API_KEY:-}" ] && echo 已配置 || echo 未配置)）"

for _ in $(seq 1 120); do
  if grep -q "Started WhatIfLabApplication" "$LOG" 2>/dev/null; then
    WIN_PID="$(resolve_win_pid)"
    if [ -n "$WIN_PID" ]; then
      echo "$WIN_PID" > "$PIDFILE"
      echo "应用已就绪：端口 $PORT，Windows pid=$WIN_PID（已写入 $PIDFILE）"
    else
      echo "$LAUNCH_PID" > "$PIDFILE"
      echo "应用已就绪：端口 $PORT，但未反查到 Windows pid，pid 文件暂存 MSYS pid=$LAUNCH_PID"
    fi
    grep -m1 'Started WhatIfLabApplication' "$LOG" | tr -d '\r'
    exit 0
  fi
  if grep -qE "APPLICATION FAILED TO START|Error starting ApplicationContext" "$LOG" 2>/dev/null; then
    echo "启动失败，日志尾部："
    tail -40 "$LOG"
    exit 1
  fi
  if ! kill -0 "$LAUNCH_PID" 2>/dev/null; then
    echo "java 进程已退出，日志尾部："
    tail -40 "$LOG"
    exit 1
  fi
  sleep 1
done

echo "启动超时（120s），日志尾部："
tail -40 "$LOG"
exit 1
