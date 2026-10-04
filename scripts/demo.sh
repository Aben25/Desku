#!/usr/bin/env bash
# Runs the afternoon-planning demo on a connected phone or emulator through the debug hooks,
# typing the user's lines instead of speaking them, and the handwritten list instead of the
# camera. Needs a debug build with an API key (local.properties anthropic.apiKey=... or Settings).
#   scripts/demo.sh            # uses the first adb device
#   ANDROID_SERIAL=xyz scripts/demo.sh
set -euo pipefail
cd "$(dirname "$0")/.."
ADB="${ADB:-adb}"
OUT=demo/run-$(date +%Y%m%d-%H%M%S)
mkdir -p "$OUT"

spoke_count() { $ADB logcat -d -s DeskBuddy:I | grep -c "SPOKE" || true; }
cast() { $ADB shell "am broadcast -p com.deskbuddy -a com.deskbuddy.debug.$1 $2" >/dev/null; }

say() {
  local before; before=$(spoke_count)
  echo "YOU:   $1"
  cast SAY "--es text \"$1\""
  for _ in $(seq 1 120); do
    sleep 1
    [[ $(spoke_count) -gt $before ]] && break
  done
  $ADB logcat -d -s DeskBuddy:I | grep -E "REPLY|ERROR" | tail -1 | sed -E 's/.*DeskBuddy: (REPLY|ERROR) /BUDDY: /'
  $ADB exec-out screencap -p > "$OUT/$2.png"
}

$ADB shell am start -n com.deskbuddy/.MainActivity >/dev/null
$ADB logcat -c
sleep 3
$ADB shell mkdir -p /sdcard/Android/data/com.deskbuddy/files
$ADB push demo/handwritten-todo.jpg /sdcard/Android/data/com.deskbuddy/files/todo.jpg >/dev/null
cast PHOTO "--es name todo.jpg"   # the next "look" uses this photo instead of the camera

say "Help me plan my afternoon. Here's my list." 1-plan
say "Okay, let's go with the grant intro." 2-choose
say "Remember that I'm working on this." 3-remember
cast NEW_CONVERSATION ""          # "later": a fresh conversation, only saved memories carry over
say "What was I supposed to focus on?" 4-recall
echo "Screenshots in $OUT"
