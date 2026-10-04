#!/usr/bin/env bash
# Offline sanity check for the wake phrase on a Mac: synthesizes phrases with `say`, runs them
# through the same Vosk model and grammar the app uses, and prints which ones would wake it.
# Synthetic voices are a smoke test, not a substitute for a real phone mic in a real room.
set -euo pipefail
cd "$(dirname "$0")/.."
MODEL=app/src/main/assets/model-en-us
[[ -d $MODEL ]] || { echo "Run scripts/fetch-wake-model.sh first"; exit 1; }
WORK=$(mktemp -d); trap 'rm -rf "$WORK"' EXIT
python3 -m venv "$WORK/venv" && "$WORK/venv/bin/pip" install -q vosk 2>/dev/null
i=0
for voice in Samantha Daniel Karen Moira Rishi Tessa; do
  for phrase in "Hey Desku" "Hey, Desku!" "Hey just school" "This is cool" "Thank you" "Hey, do you" "Hey Buddy" "Help me plan my afternoon" "The desk is clean" "Hey dude"; do
    i=$((i+1))
    say -v "$voice" -o "$WORK/s$i.aiff" "$phrase" 2>/dev/null || continue
    afconvert -f WAVE -d LEI16@16000 -c 1 "$WORK/s$i.aiff" "$WORK/s$i.wav"
    echo "$WORK/s$i.wav|$voice|$phrase"
  done
done > "$WORK/list.txt"
"$WORK/venv/bin/python" - "$MODEL" "$WORK/list.txt" 2>/dev/null <<'PY'
import json, sys, wave
from vosk import Model, KaldiRecognizer, SetLogLevel
SetLogLevel(-1)
model = Model(sys.argv[1])
# Keep in sync with WakeWord.kt (VARIANTS + DECOYS + "[unk]").
VARIANTS = ["hey desk you", "hey desk who", "hey desk coo", "hey desk two", "hey desk do", "hey desk clue", "hey desk school", "hey desk cool"]
DECOYS = ["hey", "desk", "hey desk", "hey just", "hey this", "school", "cool", "discuss", "hey there", "you", "hey do you", "thank you", "hey buddy", "the desk", "desktop", "hey dude"]
grammar = json.dumps(VARIANTS + DECOYS + ["[unk]"])
def heard(js, field):
    s = json.loads(js).get(field, "").strip()
    return any(s == v or s.startswith(v + " ") or s.endswith(" " + v) or (" " + v + " ") in s for v in VARIANTS)
should = wrong = 0
for line in open(sys.argv[2]):
    path, voice, text = line.rstrip("\n").split("|")
    wf = wave.open(path, "rb"); rec = KaldiRecognizer(model, 16000, grammar); hit = False
    while (data := wf.readframes(1600)):
        hit |= heard(rec.Result(), "text") if rec.AcceptWaveform(data) else heard(rec.PartialResult(), "partial")
    hit |= heard(rec.FinalResult(), "text")
    want = "desku" in text.lower()
    should += want; wrong += hit != want
    print(f"{'WAKE ' if hit else 'quiet'}  {voice:9} {text}{'   <-- WRONG' if hit != want else ''}")
print(f"\n{wrong} wrong out of {sum(1 for _ in open(sys.argv[2]))} clips ({should} should wake)")
PY
