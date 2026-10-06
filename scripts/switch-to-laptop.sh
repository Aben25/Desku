#!/usr/bin/env bash
# Fallback: run Desku on this laptop again (phone on USB), and stop the cloud engine so only
# one engine runs. Copies the cloud's data back first so nothing added there is lost.
set -euo pipefail
cd "$(dirname "$0")/.."
export PATH="$PATH:$HOME/Library/Android/sdk/platform-tools"
APP=desku-engine

LOCK=/tmp/desku-switch.lock
if ! mkdir "$LOCK" 2>/dev/null; then
  echo "Another Desku switch is running (or crashed: remove $LOCK). Not starting." >&2
  exit 1
fi
trap 'rmdir "$LOCK"' EXIT

if ! adb get-state >/dev/null 2>&1; then
  echo "The laptop can't see the phone. Plug it in over USB first; nothing was changed." >&2
  exit 1
fi

# Stop any laptop engine first so it can't overwrite the data we're about to bring back.
pkill -f "start-desku.sh" 2>/dev/null || true
pkill -f "tsx --env-file=.env src/index.ts" 2>/dev/null || true
mkdir -p backups && [ -f server/data/desku.json ] && cp server/data/desku.json "backups/desku-$(date +%Y%m%d-%H%M%S).json"

echo "Copying the cloud's data back…"
tgz="/tmp/desku-cloud-$(date +%s).tgz"
if fly ssh console -a "$APP" -C "sh -c 'tar czf $tgz -C /data .'" >/dev/null 2>&1 && fly ssh sftp get "$tgz" "$tgz" -a "$APP" >/dev/null 2>&1; then
  mkdir -p server/data && tar xzf "$tgz" -C server/data && rm -f "$tgz" && rm -f server/data/._* server/data/pages/._*
else
  echo "(couldn't fetch cloud data; keeping the laptop's copy)"
fi

echo "Stopping the cloud engine…"
for id in $(fly machines list -a "$APP" -q 2>/dev/null); do fly machine stop "$id" -a "$APP" >/dev/null || true; done

echo "Pointing the phone at the laptop…"
adb shell "run-as com.deskbuddy sed -i 's#<string name=\"serverUrl\">[^<]*</string>#<string name=\"serverUrl\">ws://localhost:8787</string>#' shared_prefs/settings.xml"
# Restart the app so it reads the new address. "am force-stop" is refused while the app is the
# locked kiosk (lock task mode), so kill its process as the app's own user; Android relaunches it.
adb shell "run-as com.deskbuddy sh -c 'kill \$(pidof com.deskbuddy)'" 2>/dev/null || adb shell am force-stop com.deskbuddy
sleep 3

echo "Starting the laptop engine and USB link (keeps running in the background)…"
# setsid-style detach: survives the terminal that ran this script closing.
nohup caffeinate -is scripts/start-desku.sh >/tmp/desku-keeper.log 2>&1 </dev/null &
disown
sleep 2
adb shell am start -n com.deskbuddy/.MainActivity >/dev/null 2>&1 || true
echo "Done. Keep the phone on USB."
