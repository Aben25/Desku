#!/usr/bin/env bash
# One command for the demo: keeps the Desku engine running on this laptop (restarts it if it
# ever exits) and keeps the USB link to the desk phone alive (re-adds `adb reverse` every few
# seconds, so replugging the cable just works).
# Usage: scripts/start-desku.sh        Stop: Ctrl-C (or kill this script)
set -u
cd "$(dirname "$0")/.."
export PATH="$PATH:$HOME/Library/Android/sdk/platform-tools"
LOG="${DESKU_LOG:-/tmp/desku-engine.log}"

engine_up() { pgrep -f "tsx --env-file=.env src/index.ts" >/dev/null; }

echo "Desku keeper started. Engine log: $LOG"
while true; do
  if ! engine_up; then
    echo "$(date +%H:%M:%S) starting engine"
    (cd server && nohup npx tsx --env-file=.env src/index.ts >>"$LOG" 2>&1 &)
    sleep 8
  fi
  if adb get-state >/dev/null 2>&1 && ! adb reverse --list 2>/dev/null | grep -q "tcp:8787"; then
    adb reverse tcp:8787 tcp:8787 >/dev/null && echo "$(date +%H:%M:%S) phone linked"
    adb shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1
    adb shell am start -n com.deskbuddy/.MainActivity >/dev/null 2>&1
  fi
  sleep 3
done
