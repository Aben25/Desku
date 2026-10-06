#!/usr/bin/env bash
# Move Desku from this laptop to the cloud engine (Fly), for a desk phone on Wi-Fi.
# 1) checks the phone has internet  2) stops the laptop engine (only one engine may run, or
# check-ins and calls happen twice)  3) deploys the latest code  4) copies today's data
# (to-dos, memories, focus, pages, brain session)  5) points the phone at the cloud.
# Back to the laptop: scripts/switch-to-laptop.sh
set -euo pipefail
cd "$(dirname "$0")/.."
export PATH="$PATH:$HOME/Library/Android/sdk/platform-tools"
APP=desku-engine
URL="https://$APP.fly.dev"

# One switch at a time: two at once fight over which engine runs and whose data wins.
LOCK=/tmp/desku-switch.lock
if ! mkdir "$LOCK" 2>/dev/null; then
  echo "Another Desku switch is running (or crashed: remove $LOCK). Not starting." >&2
  exit 1
fi
trap 'rmdir "$LOCK"' EXIT

echo "1/5 Checking the phone's internet…"
if ! adb get-state >/dev/null 2>&1; then
  echo "The laptop can't see the phone. Plug it in over USB (and allow USB debugging if asked); nothing was changed." >&2
  exit 1
fi
if ! adb shell "ping -c 1 -W 3 8.8.8.8 >/dev/null 2>&1"; then
  echo "The phone has no internet. Join Wi-Fi on the phone first; nothing was changed." >&2
  exit 1
fi

echo "2/5 Stopping the laptop engine…"
pkill -f "start-desku.sh" 2>/dev/null || true
pkill -f "tsx --env-file=.env src/index.ts" 2>/dev/null || true
sleep 2

echo "3/5 Deploying the latest engine to ${URL}…"
voice=$(grep -E '^LIVE_VOICE=' server/.env | cut -d= -f2- || true)
[ -n "$voice" ] && fly secrets set "LIVE_VOICE=$voice" --stage -a "$APP" >/dev/null
fly deploy --ha=false -a "$APP" 2>&1 | grep -E "good state|Error|error" || true
# A machine stopped earlier stays stopped after a deploy: start it.
for id in $(fly machines list -a "$APP" -q); do fly machine start "$id" -a "$APP" >/dev/null 2>&1 || true; done
for _ in $(seq 1 30); do curl -sf "$URL/healthz" >/dev/null && break; sleep 2; done

echo "4/5 Copying today's data to the cloud…"
# The state file goes in as desku.incoming.json, swapped in when the engine restarts, so the
# running engine can't save its older copy over it. Keep a local backup either way.
mkdir -p backups && cp server/data/desku.json "backups/desku-$(date +%Y%m%d-%H%M%S).json"
stage=$(mktemp -d)
cp -R server/data/. "$stage/"
mv "$stage/desku.json" "$stage/desku.incoming.json"
rm -rf "$stage/lost+found"
tgz="/tmp/desku-data-$(date +%s).tgz"
COPYFILE_DISABLE=1 tar czf "$tgz" -C "$stage" .
rm -rf "$stage"
fly ssh sftp put "$tgz" "$tgz" -a "$APP" >/dev/null
fly ssh console -a "$APP" -C "sh -c 'tar xzf $tgz -C /data && rm $tgz'" >/dev/null
rm -f "$tgz"
fly apps restart "$APP" >/dev/null

echo "5/5 Pointing the phone at the cloud…"
adb shell "run-as com.deskbuddy sed -i 's#<string name=\"serverUrl\">[^<]*</string>#<string name=\"serverUrl\">wss://$APP.fly.dev</string>#' shared_prefs/settings.xml"
# Restart the app so it reads the new address. "am force-stop" is refused while the app is the
# locked kiosk (lock task mode), so kill its process as the app's own user; Android relaunches it.
adb shell "run-as com.deskbuddy sh -c 'kill \$(pidof com.deskbuddy)'" 2>/dev/null || adb shell am force-stop com.deskbuddy
sleep 3
adb reverse --remove tcp:8787 2>/dev/null || true
adb shell input keyevent KEYCODE_WAKEUP
adb shell am start -n com.deskbuddy/.MainActivity >/dev/null

token=$(grep -E '^DEVICE_TOKEN=' server/.env | cut -d= -f2-)
for _ in $(seq 1 30); do
  if curl -s "$URL/api/overview" -H "Authorization: Bearer $token" | grep -q '"device":true'; then
    echo "Done: the phone is talking to $URL over its own Wi-Fi. You can unplug it."
    exit 0
  fi
  sleep 2
done
echo "The phone hasn't connected yet. Check its Wi-Fi, or go back with scripts/switch-to-laptop.sh" >&2
exit 1
