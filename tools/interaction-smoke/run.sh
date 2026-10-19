#!/bin/bash
# 起服 -> 跑真实客户端交互冒烟 -> 停服。退出码即断言结果。
# 用法：SERVER_DIR=/path/to/server MC_PORT=25599 ./run.sh
set -u
TOOL_DIR="$(cd "$(dirname "$0")" && pwd)"
SERVER_DIR="${SERVER_DIR:-$PWD}"
MC_PORT="${MC_PORT:-25599}"
PAPER_JAR="${PAPER_JAR:-paper.jar}"
BOOT_TIMEOUT="${BOOT_TIMEOUT:-180}"

cd "$SERVER_DIR" || exit 1
rm -f console.log cmd.fifo exit.code
mkfifo cmd.fifo
( java -Xms512M -Xmx2G -jar "$PAPER_JAR" nogui < cmd.fifo > console.log 2>&1; echo $? > exit.code ) &
SRV=$!
exec 3>cmd.fifo

for i in $(seq 1 "$BOOT_TIMEOUT"); do
  grep -q 'Done (' console.log 2>/dev/null && break
  sleep 1
done
if ! grep -q 'Done (' console.log; then echo "BOOT TIMEOUT"; tail -40 console.log; kill $SRV 2>/dev/null; exit 1; fi
echo "=== booted after ${i}s ==="
sleep 2

MC_PORT="$MC_PORT" node "$TOOL_DIR/bot.js"
RC=$?

echo "stop" >&3
for i in $(seq 1 60); do [ -f exit.code ] && break; sleep 1; done
echo "=== server exit: $(cat exit.code 2>/dev/null) ==="
exit $RC
