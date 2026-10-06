# Desku

> Your old phone already has eyes, ears and a voice. We give it a brain and a useful job.

Desku turns a spare Android phone (Android 8.0+) into a desk companion: a little blue blob with round glasses, bushy white brows and a mustache. It sits on a stand on Wi-Fi. You can talk to it, show it things through the camera, and ask it to remember what matters. It helps you pick one thing to work on, then checks in when the focus timer ends.

The app follows design **1a "Nightlight"** from the Claude Design handoff in `design/handoff/`. It has six screens that change with what Desku is doing: idle (dozing), listening, speaking, camera read, timer check-in, and memories & privacy. Desku's face shows the state (dozing, listening, thinking, talking, muted) instead of a spinner. The dock has three buttons: camera, **Talk**, and memories.

## Backend engine (GPT-Live + Agents API)

[`server/`](server/README.md) is the newer backend. It uses `gpt-live-1` for full-duplex voice and a saved Agents API agent called "Desku" as the brain. It adds:

- check-ins where Desku speaks first
- the user's apps, through Composio
- phone calls when the user is away, through AgentPhone

[`agent/`](agent/README.md) drives the same agent from the terminal with curl.

**Engine mode in the kiosk app.** When **Settings → Desku engine** has a server address, the app stops using Claude on the phone and becomes the engine's device:

- **Mic up, voice down.** It streams the mic up and plays Desku's voice back. Both use raw 24 kHz PCM16, with the phone's call-grade echo cancellation and the loudspeaker on.
- **Camera.** It answers the engine's camera requests with the usual countdown overlay.
- **Screen.** It shows the engine's memories, focus timer, check-ins and app connect links.
- **Buttons.** Talk, the wake phrase and check-ins open the voice session. Screen buttons are sent to the engine as taps.

With the address left blank, the app works as before.

To try it on a USB-connected phone:

1. Add `desku.serverUrl=ws://localhost:8787` and `desku.deviceToken=<DEVICE_TOKEN from server/.env>` to `local.properties`.
2. Run these in order:

```bash
cd server && npm start
```

```bash
adb reverse tcp:8787 tcp:8787
```

```bash
./gradlew :app:installDebug
```

On Wi-Fi, use `ws://<computer's LAN IP>:8787` instead. Debug builds allow plain `ws://`; release builds need `wss://`.

Tested on Oct 5, 2026 on a Pixel 7a over USB:

- Desku greeted the user and asked what they were working on.
- The user answered out loud through the phone's mic, and Desku set their focus from it.
- An email question produced a Gmail connect link on screen.

## Locked kiosk (device owner)

Desku can lock a phone so it runs only Desku. There's no home screen, app switcher, notifications, status bar or lock screen. It boots straight into Desku, keeps the screen on while charging, can't be uninstalled, and blocks factory reset and safe boot. The code is in `app/src/main/java/com/deskbuddy/kiosk/`.

1. The phone must have no accounts signed in.
2. Install the app.
3. Run:

```bash
adb shell dpm set-device-owner com.deskbuddy/.kiosk.DeskuAdmin
```

The admin PIN is `desku.kioskPin` in `local.properties`, which isn't committed.

**To get out:** hold the top of the screen for 5 seconds and enter the PIN. **Settings** opens the app settings; **Unlock phone** removes the kiosk and device owner. Debug builds can also do this over USB:

```bash
adb shell am broadcast -p com.deskbuddy -a com.deskbuddy.debug.KIOSK_EXIT --es pin <PIN>
```

If the PIN is lost, the only way out is a factory reset from recovery mode.

Inside the kiosk, app connect links can't open a browser, so connect apps from the agent page on a computer.

## The demo

1. "Hey Desku… help me plan my afternoon." It offers **Show Desku** or **I'll say it**.
2. You show a handwritten to-do list. The list it read appears on screen with the one it suggests highlighted, plus **Yes, that one** and **Pick another**.
3. You choose one together. It offers a 25-minute focus timer.
4. "Remember that I'm working on this." The memory shows up on screen, written out in full ("…chose to focus on finishing the grant intro").
5. Later, in a new conversation: "What was I supposed to focus on?" It answers from that memory.

## What works, and how it was checked

| Feature | Status | How it was verified |
|---|---|---|
| Voice conversation (tap **Talk**) | Built | Android `SpeechRecognizer` in, `TextToSpeech` out, follow-up listening after each reply. Exercised on an emulator with typed input (the debug hooks below). **Not yet tested with a real mic on a real phone.** |
| Claude brain | Built | Official Anthropic Java SDK, `claude-opus-5-5` at low effort. A scripted-API unit test covers the whole demo. The emulator sent a real request and got the expected 401 for an invalid key, which shows the SDK runs on Android. **Not yet run against a live key.** |
| Camera | Built | The camera is always on and never shown. Desku wakes and follows you with his eyes when you sit down, dozes when you leave, and takes a photo instantly (no countdown) when he needs to look. Checked on a Pixel 7a: the front camera stays open in the background and the app doesn't crash. Turn it off with the Camera switch on the Memories screen. |
| Memory | Built | Saves only when your latest words ask for it ("remember…", "note that…", "don't forget…") or you say yes to an offer. This rule is enforced in code (`MemoryPolicy`), not just in the prompt. Delete one memory or all of them from the screen, or by voice ("forget that"). |
| Focus timer + follow-up | Built | Timer chip on screen, a chime, then a check-in ("How did it go?") with the mic open for your reply. Tested on the emulator. |
| Mute mic / camera off | Built | Mute stops the wake listener as well; on the emulator, Android's green mic indicator disappears. Camera off makes Claude say it can't look instead of pretending it can. |
| Reacts to voice | Built | Listening, thinking and talking share one steady screen, which fixed the flicker. An orb-style aura swells with your voice while he listens and with his own voice while he talks; his mouth opens with the actual loudness of his speech in engine mode. |
| Wake phrase "Hey Desku" | Built (added last) | Runs on the phone with Vosk. "Desku" isn't a dictionary word, so the grammar lists how the model hears it ("hey desk who", "hey desk coo"…) plus sound-alike decoys. `scripts/check-wake-word.sh` got 0 wrong out of 60 synthetic clips: 12 of 12 "Hey Desku" across 6 voices woke it, and none of the near-misses did ("hey just school", "this is cool", "thank you"…). The listener runs on the Pixel 7a. **Not yet tested with a real room mic.** If the model isn't bundled, the UI says so and you use **Talk**. |
| On-screen lists and buttons | Built | A `show_on_screen` tool lets Claude put what it read and up to three tap targets next to the spoken reply. It needs no extra round trip: the tool result is sent with your next message. Covered by unit tests; every screen was checked on a Pixel 7a. |
| Kiosk | Built | Keeps the screen on and full-screen, dims after 3 idle minutes, can be set as the home app, and has a "Pin to screen" option in settings. |

**Not done yet:** streaming replies (it speaks once the whole reply is ready, usually a couple of seconds); interrupting by voice (you tap **Interrupt**); languages other than English; battery and heat tuning for 24/7 use; release signing; shrinking the 116 MB debug APK (R8 is off).

## Your face as Desku (avatar)

This is optional and off: the app ships with the drawn Desku. Desku can be you instead of the drawn blue character. `scripts/make-avatar.sh` takes one photo and renders four short looping clips of that face with [LivePortrait](https://github.com/KwaiVGI/LivePortrait), running locally on the Mac: idle (small natural movement), listening (a warm, attentive smile), talking (mouth moving), and thinking (glancing aside). It also makes a still. The app plays whichever loop matches Desku's state, in a circle with the same rings and glow. Muted shows the still in grey. When `app/src/main/assets/avatar/` is empty, the app falls back to the drawn Desku.

- **It's a loop, not lip sync.** The talking clip moves the mouth while Desku speaks, but it doesn't match the exact words. Real lip sync needs a live service (ElevenLabs or similar).
- **Privacy.** The photo and renders stay on the Mac and the phone. The bundled clips are of a real person, so don't publish the APK unless that person agrees.
- **License.** LivePortrait's code is MIT, but its face detector (InsightFace) is for non-commercial use only. Fine for a demo; replace it before selling anything.

One-time setup, about 1.5 GB:

```bash
git clone --depth 1 https://github.com/KwaiVGI/LivePortrait.git ~/LivePortrait
cd ~/LivePortrait && uv venv -p 3.11 .venv
grep -v gradio requirements_base.txt > req.txt
uv pip install -p .venv/bin/python -r req.txt torch==2.3.0 torchvision==0.18.0 onnxruntime "huggingface_hub[cli]<1"
.venv/bin/huggingface-cli download KwaiVGI/LivePortrait --local-dir pretrained_weights --include "liveportrait/*" "insightface/*"
```

Then make the clips (about 5 minutes on an M-series Mac) and rebuild:

```bash
LP_DIR=~/LivePortrait scripts/make-avatar.sh ~/Downloads/buddy-photos/photo06-2c1e8430.jpg
```

## Privacy model

| What | Where it goes | Kept? |
|---|---|---|
| Background audio while waiting for "Hey Desku" | Processed in memory on the phone by Vosk, then dropped | Never |
| What you say after the wake phrase or a tap on **Talk** | The phone's speech recognizer (usually Google's, which may use the network) → the text goes to Claude | In memory for the current conversation; forgotten after 10 quiet minutes |
| Camera | Always on while Desku is on screen and the camera switch is on, with no preview (Android shows its green camera dot). Face detection runs on the phone about once a second, so Desku knows you're there and where to look; those frames are dropped right away. A photo is taken, and sent to the brain, only when Desku needs to look. | Frames: never. A photo: in memory for the current conversation. |
| Memories | `memories.json` in the app's private storage | Until you delete them |
| API key | App-private settings (debug builds can preload it from `local.properties`) | Until you replace it |

Conversations are never written to disk. Mic muted means nothing is listening, not even for the wake phrase.

## Set up

Needs: JDK 17, the Android SDK (compileSdk 36), and an Anthropic API key.

```bash
scripts/fetch-wake-model.sh
```

That fetches the ~40 MB Vosk English model into `app/src/main/assets/model-en-us`. It's optional; without it there's no wake phrase.

Put your key in `local.properties`, which is gitignored. Only debug builds read it:

```
anthropic.apiKey=sk-ant-...
```

Build and install on the phone (USB debugging on):

```bash
./gradlew :app:installDebug
```

On the phone:
1. Allow the microphone and camera.
2. To make it a kiosk, press Home and choose **Desku** as the home app. It then starts on boot and the home button always comes back to it.
3. Optional: long-press the clock to open Settings, then tap **Pin to screen** (Android's screen pinning).
4. Keep it on a charger. The screen dims when idle and wakes on touch or voice.

You can also paste the key in Settings on the phone instead (long-press the clock on the idle screen), and change the model there. Mic, camera and wake-phrase switches live on the Memories screen.

## Testing without a mic

Debug builds accept adb broadcasts, so you can drive the app on an emulator:

```bash
scripts/demo.sh
```

This runs the whole demo with typed lines and `demo/handwritten-todo.jpg` in place of the camera, prints each reply, and saves screenshots. The individual hooks:

```bash
adb shell "am broadcast -p com.deskbuddy -a com.deskbuddy.debug.SAY --es text \"Help me plan my afternoon\""
adb shell "am broadcast -p com.deskbuddy -a com.deskbuddy.debug.PHOTO --es name todo.jpg"   # file in /sdcard/Android/data/com.deskbuddy/files
adb shell "am broadcast -p com.deskbuddy -a com.deskbuddy.debug.TIMER --ei seconds 10 --es task 'grant intro'"
adb shell "am broadcast -p com.deskbuddy -a com.deskbuddy.debug.WAKE"
adb shell "am broadcast -p com.deskbuddy -a com.deskbuddy.debug.NEW_CONVERSATION"
adb shell "am broadcast -p com.deskbuddy -a com.deskbuddy.debug.SCREEN --es mode checkin --es text 'grant intro'"   # listening|thinking|speaking|camera|checkin|memories|idle
```

`SCREEN` shows any screen without a brain, which is handy for reviewing the design. With `mode speaking` or `mode camera`, add `--es card '{...}'` (the same shape as `show_on_screen`) and `--es photo todo.jpg`.

These hooks are only registered in debug builds. Any app on a debug-build device could send them, so don't leave a debug build on a phone you don't control.

Unit tests (JVM, no device needed):

```bash
./gradlew :app:testDebugUnitTest
```

`BrainDemoTest` runs the real `Brain` and the real SDK against a scripted Messages API. It walks the demo, and also checks:
- memory is refused when you didn't ask for it
- a turned-off camera is reported, not faked
- the timer event reaches Claude
- a failed call rolls the conversation back
- `show_on_screen` ends a turn in one request, and its pending result survives a failed retry

It also asserts that every request only appends to the one before it: the system prompt and tools stay byte-identical, and earlier messages are never edited. That keeps prompt caching effective and keeps Claude's thinking blocks valid.

## How it's built

```
app/src/main/java/com/deskbuddy/
  MainActivity.kt          kiosk window, permissions, debug hooks
  CompanionViewModel.kt    the state machine: idle → listening → thinking → speaking (→ looking)
  Settings.kt              API key, model, wake word, camera lens, privacy switches
  brain/Brain.kt           Claude loop: 6 tools, append-only history, typed error handling
  brain/Prompt.kt          frozen per-conversation system prompt + per-turn desk status line
  memory/MemoryStore.kt    explicit memories, JSON in app storage
  memory/MemoryPolicy.kt   the "only if asked" rule
  voice/Ears.kt            one utterance via SpeechRecognizer
  voice/Voice.kt           TextToSpeech + chimes
  voice/WakeWord.kt        on-device Vosk wake phrase, separate from Ears
  camera/CameraFeed.kt     preview, countdown, one photo, camera off
  ui/Desku.kt              the character: blob, glasses, brows, mustache, 6 expressions
  ui/Screens.kt            the six Nightlight screens and the dock
  ui/Night.kt              palette, Figtree + DM Mono, glyphs, pills, switches
```

Claude's tools: `look_through_camera`, `save_memory`, `delete_memory`, `start_focus_timer`, `cancel_focus_timer`, `show_on_screen`.

Request settings:
- Thinking is adaptive (it can't be turned off on Opus 5.5) with `effort: low`, to keep spoken turns quick.
- Automatic prompt caching is on.
- `fallbacks: "default"` is set, so if a safety classifier declines a turn, the server retries it on Anthropic's recommended model.

## License

MIT. See [LICENSE](LICENSE).
