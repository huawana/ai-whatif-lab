#!/usr/bin/env bash
# 停止应用。
#
# 【为什么以端口为准，而不是以 pid 文件为准】
# 实测踩到的坑：`nohup java ... &` 拿到的 `$!` 是 MSYS 的 pid，与 Windows pid 不是同一套编号。
# 用它去 `taskkill /PID` 会打错对象 —— 表现为脚本报"已停止"，而 java 进程仍在监听端口。
# 所以这里的判定顺序是：
#   ① 从监听端口反查 Windows pid（唯一权威来源）
#   ② 退而求其次用 pid 文件（且必须确认它确实是 java 的 pid）
# 杀掉之后**必须复查端口是否真的释放**，不然"停止成功"又是一句假话。
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

PIDFILE=.hermes/app.pid
PORT="${SERVER_PORT:-8088}"

port_pid() {
  netstat -ano | grep -E "TCP.*:$PORT .*LISTENING" | awk '{print $NF}' | head -1
}

port_listening() {
  netstat -ano | grep -qE "TCP.*:$PORT .*LISTENING"
}

kill_win_pid() {
  local pid="$1"
  [ -z "$pid" ] && return 1
  local out rc alive=0
  out=$(MSYS2_ARG_CONV_EXCL='*' taskkill /PID "$pid" /F 2>&1); rc=$?
  # 【实测踩到的坑】不要靠 taskkill 的输出文案判断成败：
  #   taskkill 在中文 Windows 上输出 **GBK 字节**（"成功: 已终止 PID …"），而本脚本源码是 UTF-8 ——
  #   `grep -q "成功"` 永远匹配不上，于是一次**成功的**停止被打印成「taskkill 失败」，
  #   既吓人又掩盖真实失败（措辞错了，两个方向都错）。
  # 正确判据 = 退出码 + 进程表复核，缺一不可：
  #   只看退出码 → 遇上"报了成功但进程还在"会漏判；只看进程表 → 传进来一个不存在的 pid 也会被判成"已终止"（假成功）。
  MSYS2_ARG_CONV_EXCL='*' tasklist /FI "PID eq $pid" 2>/dev/null | grep -q " $pid " && alive=1
  if [ "$rc" -eq 0 ] && [ "$alive" -eq 0 ]; then
    echo "已终止：Windows pid=$pid"
    return 0
  fi
  echo "taskkill 未生效（pid=$pid, 退出码=$rc, 进程表残留=$alive）：$(printf '%s' "$out" | iconv -f GBK -t UTF-8 2>/dev/null || printf '%s' "$out")"
  return 1
}

TARGET="$(port_pid)"

if [ -z "$TARGET" ]; then
  # 端口没监听，可能只是没起来；顺手清理过期的 pid 文件
  if [ -f "$PIDFILE" ]; then
    echo "端口 $PORT 无监听；删除过期 pid 文件（原内容 $(cat "$PIDFILE")）"
    rm -f "$PIDFILE"
  else
    echo "端口 $PORT 无监听，应用未在运行"
  fi
  exit 0
fi

echo "端口 $PORT 的监听进程 = Windows pid $TARGET"
kill_win_pid "$TARGET" || true

# 复查：端口必须真的释放（最多等 15 秒）。这是防止"假成功"的最后一道闸。
for i in $(seq 1 15); do
  if ! port_listening; then
    rm -f "$PIDFILE"
    echo "已停止，端口 $PORT 已释放（等待 ${i}s）"
    exit 0
  fi
  sleep 1
done

echo "停止失败：端口 $PORT 仍在监听（pid $(port_pid)），请手工处理"
exit 1
