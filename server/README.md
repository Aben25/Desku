# Desku engine

This is the backend that makes the desk phone a proactive buddy:

- **GPT-Live** (`gpt-live-1`) is Desku's ears and voice.
- A saved **Agents API** agent, also called "Desku", is its brain.
- This server sits between them, runs the tools, and starts conversations when check-ins come due.

```
 phone (mic, speaker, camera) ──WS /device──► Desk engine ──WS──► GPT-Live (voice, full duplex)
                                                   │                   │ session.delegation.created
                                                   │ ◄─────────────────┘
                                                   ├──► Agents API session "Desku" (thinks, picks tools)
                                                   │       └─ function calls ──► tools here: focus, check-ins,
                                                   │                             memory, camera (asks the phone)
                                                   └──► session.commentary.append ──► GPT-Live says the answer
 scheduler ── check-in due ──► open voice session ──► brain writes the check-in ──► Desku speaks first
```

## Why it's split this way

- **Voice sessions are short.** GPT-Live costs $0.05 a minute while open. The engine opens a session on Talk, wake word or a due check-in, and closes it after `IDLE_CLOSE_SECONDS` of quiet.
- **The brain persists.** One Agents API session, created from the saved agent, holds the whole conversation across voice sessions. The engine stores its ID in `data/desku.json`. If you change `../agent/desku-agent.json`, the engine updates the saved agent on startup and starts a fresh brain session, because sessions copy the agent config when they're created.
- **GPT-Live uses client delegation.** The engine collects the transcript itself and sends what the user said since the last delegation to the brain. Mid-turn text, such as "hold it steady", is spoken only when the user has to do something (the camera countdown). Otherwise it goes to GPT-Live as silent context.
- **The memory rule is enforced in code.** `save_memory` is refused unless the user's own words ask for it ("remember…", "don't forget…") or are a yes to Desku's offer. This is ported from the Android app's `MemoryPolicy`.
- **Desku can start the conversation.** When a check-in comes due and the phone is connected, the engine opens a voice session and asks the brain for a check-in line. GPT-Live then speaks it unprompted. Check-ins that come due while the phone is offline are delivered when it reconnects.

## Your apps (Composio)

Desku can use your apps through [Composio](https://composio.dev): Google Calendar, Gmail, Slack, Notion, GitHub and 500+ more.

- **How it's wired.** On startup the engine opens one Composio session for Desku's owner (`COMPOSIO_USER_ID`, default `desku-owner`) and stores its ID in `data/desku.json`. The session's meta tools are added to the Desku agent as function tools:
  - `COMPOSIO_SEARCH_TOOLS`
  - `COMPOSIO_GET_TOOL_SCHEMAS`
  - `COMPOSIO_MULTI_EXECUTE_TOOL`
  - `COMPOSIO_MANAGE_CONNECTIONS`

  When the brain calls one, this server runs it. The Composio key stays on the server.
- **Connecting an app.** Ask Desku something that needs the app, such as "what's on my calendar today?". If it isn't connected yet, Desku says so and the Connect Link appears on the desk screen as a `{type:"link"}` message. Open it, approve, then ask again. You only do this once per app.
- **Consent.** Desku reads whenever you ask. Before it sends, posts, creates, changes or deletes anything, it says exactly what it will do and waits for your yes. This rule is in the agent instructions; unlike the memory rule, it isn't enforced in code yet.
- **Not included.** Composio's remote sandbox tools (bash and workbench) are left out.

You can also connect apps from the agent page at `/agent.html`: enter the device token, then use the "Connect an app" buttons. They call `POST /api/apps/connect` with `{"app":"gmail"}` and open the returned link.

To enable it, set `COMPOSIO_API_KEY` (a Platform project key, `ak_…`) in `.env`. To try it from the terminal:

```bash
npx tsx --env-file=.env scripts/smoke-apps.ts "What's on my calendar for the rest of today?"
```

## Calling you when you're away (AgentPhone)

Desku has its own phone number, +1 628-284-1240, on [AgentPhone](https://agentphone.ai/skills.md). When a check-in comes due and you're not at the desk, Desku calls your phone instead.

- **When it calls:**
  - The desk phone is offline when the check-in comes due: Desku calls right away.
  - Desku asks the check-in at the desk and hears no reply for `AWAY_CALL_AFTER_SECONDS` (default 60): it closes the voice session and calls.
  - If you say anything at the desk in that time, the call is cancelled.
- **How the call works.** It runs on AgentPhone's hosted voice AI. The engine gives it the check-in, the time and your saved memories. It opens with "Hi, it's Desku, your AI desk buddy…" and aims to last under a minute.
- **After the call.** The transcript goes to the brain, which can update your focus or schedule another check-in. The memory rule applies to what you said on the call: it only saves something if you asked it to remember it.
- **Never at night.** Desku doesn't call during `QUIET_HOURS` (default 22-8, local time). It places one call at a time.

**Calls on request.** Desku's `call_phone` tool covers two cases:

- "Call me" rings your phone.
- "Call my friend at +1 628 …" calls that number for you. Desku reads the number back and waits for your yes. On the call it says it's an AI calling on your behalf, delivers your message, and then tells you at the desk what the person said.

Only US and Canada numbers work. The server refuses a call to anyone else unless your own words asked for a call.

**Desk controls.** With `control_desk`, Desku can do these when you ask:

- turn the camera on or off
- close the camera screen
- mute the mic
- show or hide your memories
- end the conversation after it says goodbye

Unmuting stays a screen tap, because a muted mic can't hear "unmute".

To turn it on, set these in `.env`:

- `AGENTPHONE_API_KEY`: from the AgentPhone dashboard, under Settings → API Keys.
- `OWNER_PHONE`: your phone, in E.164 format, e.g. `+14155551234`.

The AgentPhone account needs a payment method on file before it can make outbound calls.

To place a test call right now:

```bash
npx tsx --env-file=.env scripts/call-me.ts
```

## Setup

You need Node 22+ and an OpenAI API key with `api.agents.read`, `api.agents.write` and `api.responses.write`, on a paid tier, since GPT-Live isn't available on the free tier.

```bash
cd server
npm install
cp .env.example .env
```

In `.env`, set `OPENAI_API_KEY` and a long random `DEVICE_TOKEN`. To reuse the agent made by `../agent/desku.sh create-agent`, also set `DESKU_AGENT_ID`. If you leave it empty, the engine creates one.

```bash
npm start
```

Then open http://localhost:8787 on a laptop to use the browser test client. Paste the `DEVICE_TOKEN`, press **Connect**, then **Talk**. Use headphones or a laptop with echo cancellation, because GPT-Live is full duplex and will otherwise hear itself.

## Agent page (`http://localhost:8787/agent`)

A read-only page that shows what Desku is connected to and what it keeps. Paste the `DEVICE_TOKEN` to open it; it refreshes every 5 seconds. It shows:

- **Connections.** Desk phone, voice (GPT-Live), brain (Agents API session), your apps (one chip per Composio app, with its status), phone calls (AgentPhone) and web search.
- **Right now.** Current focus, recent focus, and upcoming check-ins.
- **Memories** Desku has saved.
- **Tools** the agent can call: desk tools, hosted tools and Composio tools.
- **IDs and instructions** for the agent and brain session.

The page reads `GET /api/overview`, which needs `Authorization: Bearer DEVICE_TOKEN` because memories and focus are personal. Composio connections are cached for 30 seconds.

## Pages Desku makes (`/p/<id>`)

Some answers are better seen than heard, like open tickets, today's schedule or a comparison. For those, Desku writes a self-contained web page, the engine stores it in `data/pages/` and serves it at `/p/<id>`, and the desk screen opens it. The agent page lists them under **Pages Desku made**, where you can open or delete them.

- **The link is the key.** IDs are 128 random bits, so the desk screen opens a page without the token. Deleting needs the token (`DELETE /api/pages/<id>`).
- **Pages are sandboxed.** They often contain text from emails, tickets and the web, so they're served with a `sandbox` CSP and no `allow-same-origin`. Their scripts run, but they can't read the desk token, call the engine, fetch anything or submit forms. Scripts and styles may come from jsDelivr or cdnjs (for charts), fonts from Google Fonts, and images only as `data:` URIs. Desku bakes the data into the page; to refresh it, Desku rewrites the same page ID.
- **Limits.** 512 KB per page. The newest 50 are kept.

## Running on Fly.io (`https://desku-engine.fly.dev`)

The engine also runs on one always-on Fly Machine in `ewr`, with a 1 GB volume at `/data` for memories, check-ins, pages and the agent link. `Dockerfile`, `.dockerignore` and `fly.toml` are at the repo root, because the image also needs `agent/desku-agent.json`.

- **Deploy** from the repo root: `fly deploy --ha=false`. Keep it to one Machine: the state is one JSON file on one volume, and the engine allows one desk phone at a time. Redeploy after editing `desku-agent.json` or the server code.
- **Keys** are Fly secrets, loaded from `server/.env` (`fly secrets list` shows names only). `.env` never goes into the image.
- **The phone** connects to `wss://desku-engine.fly.dev/device?token=DEVICE_TOKEN`. The agent page is at `https://desku-engine.fly.dev/agent`, and pages at `https://desku-engine.fly.dev/p/<id>`.
- **Run one engine at a time.** A local engine and the Fly one share the same saved agent and Composio user, but each has its own memories, check-ins and away calls.
- **Logs:** `fly logs --app desku-engine`.

## Device protocol (`ws://HOST:8787/device?token=DEVICE_TOKEN`)

All audio is raw mono PCM16, little-endian, 24 kHz, sent as binary frames in both directions. Send mic audio in roughly 100 ms chunks.

| Phone → engine (JSON) | |
|---|---|
| `{type:"start"}` / `{type:"stop"}` | Open the voice session (Talk or wake word), or end it |
| `{type:"mute"}` / `{type:"unmute"}` | Mute the mic. The engine drops audio and tells GPT-Live |
| `{type:"camera", enabled}` | Camera privacy switch. When off, the camera tool fails honestly |
| `{type:"photo", requestId, image}` | Reply to `capture`. `image` is a base64 JPEG |
| `{type:"photo_error", requestId, reason}` | The phone couldn't take the photo |
| `{type:"forget", id}` / `{type:"forget_all"}` | Delete memories from the screen |
| `{type:"say", text}` | A tap on a screen button ("10 more minutes"). The brain handles it and Desku answers out loud |

| Engine → phone (JSON) | |
|---|---|
| `{type:"state", live, muted, camera, thinking}` | `live` is `idle`, `connecting` or `open` |
| `{type:"transcript", role, delta}` | Live captions for both sides |
| `{type:"desk", memories, focus, checkins}` | Sent on connect and on every change |
| `{type:"capture", requestId, countdownSeconds}` | Show the preview, count down, take one photo |
| `{type:"checkin", about}` | A check-in is starting. Wake the screen |
| `{type:"link", url, label}` | An app Connect Link for the user to open |
| `{type:"calling", about}` | Desku is phoning the user about a check-in |
| `{type:"error", message}` | Shown to the user |

## Phone without internet: USB bridge

If the desk phone isn't on Wi-Fi, keep it on USB and relay it to a remote engine (Fly by default) through the laptop:

```bash
npx tsx scripts/usb-bridge.ts https://desku-engine.fly.dev
```

```bash
adb reverse tcp:8787 tcp:8787
```

The phone keeps `ws://localhost:8787` as its engine address. Once the phone joins Wi-Fi, set the address to `wss://desku-engine.fly.dev` and stop the bridge.

## Tests

```bash
npm test
```

There are 26 unit tests, using a fake GPT-Live, a fake brain and a fake phone, plus a mock WebSocket for the GPT-Live client and mock AgentPhone and Composio APIs. They cover:

- audio flow and voice turns
- delegation answered with the right delegation ID
- refusing memories the user didn't ask for
- the camera round trip, and the camera switched off
- proactive check-ins, including holding them while the phone is offline
- mute, idle close, and brain failure
- away calls: offline desk, no reply, a reply cancelling the call, quiet hours, the call transcript going back to the brain, and AgentPhone errors
- apps: Composio tools routed through the session, Connect Links reaching the screen, and Composio errors with their log IDs

Two live checks need `.env` filled in:

```bash
npx tsx --env-file=.env scripts/smoke-brain.ts
```

This runs the brain alone against the real Agents API.

```bash
npx tsx --env-file=.env scripts/fake-phone.ts "Hey Desku, can you read my to-do list? I'm holding it up to the camera."
```

With the server running, this one is a stand-in phone. It speaks the lines with macOS `say` into the real GPT-Live session, answers camera requests with `demo/handwritten-todo.jpg`, prints both transcripts and saves Desku's audio as a WAV.

## Verified (Oct 4–5, 2026, live API)

These runs used `fake-phone.ts`, so the audio was synthesized speech from a file, not a real microphone:

- **Voice in, voice out through `gpt-live-1`.** Desku delegated the request, said "one sec" while it waited, then spoke the brain's answer.
- **Reading the demo to-do list through the camera tool.** The image went to the brain as a tool result. It read all five items in 3 of 4 runs. In one run it said it couldn't read the photo.
- **Focus with a 1-minute check-in.** The check-in fired on time and Desku started speaking on its own: "How's that grant intro coming along?"
- **Memories.** "Remember the grant is due Friday at noon" was saved. "The grant is due Friday, just so you know" was not saved, and Desku offered to remember it instead.
- **The brain session carried context across separate voice sessions.**
- **A wrong device token is rejected with 401.**

## Not verified yet

- The browser test client renders and speaks the same protocol as `fake-phone.ts`, but it hasn't been tried with a real microphone.
- The Android app still talks to Claude directly. It isn't connected to this engine yet.
- There is no barge-in flush: if you interrupt, audio already queued on the client finishes playing.
- Away calls pass the unit tests against a mock AgentPhone API, but no real call has been placed yet.
- Apps were tested live up to the Connect Link: Desku searched Composio, saw that Google Calendar wasn't connected, and produced the link. No app has been connected and read from yet.
- There is no ambient listening. It's planned.
