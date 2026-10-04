#!/usr/bin/env bash
# Turns one photo of a person into Desku's four looping face clips (idle, listening, talking,
# thinking) plus a still, in app/src/main/assets/avatar/. Rendering runs locally with
# LivePortrait (https://github.com/KwaiVGI/LivePortrait); the photo never leaves this Mac.
#
#   scripts/make-avatar.sh path/to/photo.jpg [crop]
#
# crop is ffmpeg's w:h:x:y on LivePortrait's 1024×1024 output, chosen so the face sits in the
# middle of the circle (default 720:720:250:70 fits a selfie with the face slightly right of
# center). Needs: LP_DIR pointing at a LivePortrait checkout with .venv and pretrained_weights
# (see "Avatar" in README.md), and ffmpeg.
#
# Note: LivePortrait's face detector (InsightFace) is licensed for non-commercial use.
set -euo pipefail
cd "$(dirname "$0")/.."
PHOTO="${1:?usage: scripts/make-avatar.sh photo.jpg [crop]}"
CROP="${2:-720:720:250:70}"
LP_DIR="${LP_DIR:?set LP_DIR to a LivePortrait checkout}"
DRIVING="$LP_DIR/assets/examples/driving"
OUT=app/src/main/assets/avatar
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

# state | LivePortrait driving clip | start s | length s | slow-down
# Chosen by eye from the driving clips: d19 is near-still (idle), d0 a warm attentive smile
# (listening), d20 steady speech (talking), and the head tilt and upward glance in d11 at
# 4.2 s, slowed down so it reads as pondering (thinking).
CLIPS=(
  "idle|d19.mp4|0|6|1"
  "listening|d0.mp4|0|3.1|1"
  "talking|d20.mp4|0.5|6|1"
  "thinking|d11.mp4|4.2|1.2|1.5"
)

mkdir -p "$OUT"
for row in "${CLIPS[@]}"; do
  IFS='|' read -r state clip start len slow <<< "$row"
  echo "== $state ($clip ${start}s +${len}s)"
  ffmpeg -v error -y -ss "$start" -t "$len" -i "$DRIVING/$clip" -an "$WORK/drive-$state.mp4"
  (cd "$LP_DIR" && PYTORCH_ENABLE_MPS_FALLBACK=1 .venv/bin/python inference.py \
      -s "$PHOTO" -d "$WORK/drive-$state.mp4" -o "$WORK/render" >/dev/null 2>&1)
  rendered=$(ls "$WORK/render/"*"--drive-$state.mp4")
  # Crop to the face, shrink to 480², and play forward then backward so the loop has no seam.
  # Baseline H.264 so 2017-era phones decode it without trouble.
  ffmpeg -v error -y -i "$rendered" -filter_complex \
    "[0:v]setpts=$slow*PTS,fps=25,crop=$CROP,scale=480:480:flags=lanczos,split[a][b];[b]reverse[r];[a][r]concat=n=2:v=1[v]" \
    -map "[v]" -c:v libx264 -profile:v baseline -level 3.1 -pix_fmt yuv420p -crf 23 -an -movflags +faststart \
    "$OUT/$state.mp4"
done
ffmpeg -v error -y -i "$OUT/idle.mp4" -frames:v 1 -q:v 3 "$OUT/poster.jpg"
ls -la "$OUT"
