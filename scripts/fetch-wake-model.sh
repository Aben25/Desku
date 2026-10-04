#!/usr/bin/env bash
# Downloads the small English Vosk model (~40 MB, Apache 2.0) that powers the on-device
# "Hey Buddy" wake phrase, and places it in app/src/main/assets/model-en-us.
# Without it the app still works: the mic button is the way in, and the UI says so.
set -euo pipefail
cd "$(dirname "$0")/.."
NAME=vosk-model-small-en-us-0.15
DEST=app/src/main/assets/model-en-us
URL="https://alphacephei.com/vosk/models/$NAME.zip"

if [[ -f "$DEST/uuid" ]]; then
  echo "Wake model already present in $DEST"
  exit 0
fi
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
echo "Downloading $URL"
curl -fsSL -o "$TMP/model.zip" "$URL"
unzip -q "$TMP/model.zip" -d "$TMP"
rm -rf "$DEST"
mkdir -p "$(dirname "$DEST")"
mv "$TMP/$NAME" "$DEST"
# Vosk's StorageService re-unpacks on the phone whenever this id changes.
uuidgen > "$DEST/uuid"
echo "Wake model installed in $DEST ($(du -sh "$DEST" | cut -f1))"
