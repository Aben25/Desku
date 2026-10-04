# Desku on the OpenAI Agents API

A small runnable app that uses `curl` and `jq` to call the [Agents API](https://developers.openai.com/api/docs/guides/agents-api/overview) directly. It saves a reusable agent named **Desku** in project `proj_2apst5kE7CvBS51WxiS1MWax`, starts sessions from that agent's ID, streams output and events, answers Desku's function calls locally, and handles errors.

## Setup

You need `bash`, `curl`, `jq` and `uuidgen`. All of these ship with macOS.

```bash
cp .env.example .env
```

Put your key in `.env` as `OPENAI_API_KEY`. The key needs the `api.agents.read`, `api.agents.write` and `api.responses.write` permissions. `.env` and `state/` are gitignored. You can also export `OPENAI_API_KEY` in your shell instead.

## Run

```bash
./desku.sh create-agent
```

This saves the agent from `desku-agent.json` and writes its ID to `state/agent_id`. Running it again reuses that agent. Pass `--force` to create a new one after you edit the JSON. Saved-agent changes only apply to new sessions.

```bash
./desku.sh start "Hey Desku, I'm working on the grant intro for 25 minutes. Check in on me after."
```

This creates a session with `agent_id` and `environment: {type: "none"}`, sends the message, and streams the first turn.

```bash
./desku.sh say "Remember the grant is due Friday at noon. What was I working on?"
```

This subscribes to the session's event stream, then sends a follow-up.

| Command | What it does |
|---|---|
| `chat` | Interactive loop on the current session |
| `tick` | Sends any due check-ins to Desku, which then checks in with you. Run it from cron, e.g. `* * * * * /path/to/agent/desku.sh tick` |
| `status` | Session status, error and pending required actions |
| `items` | Saved messages and tool calls |
| `cancel` | Cancels the active turn |
| `end` | Deletes the session |

## How it works

- **Environment `none`.** Desku answers, searches the web and calls your functions. It doesn't run code or touch files, so no sandbox, runtime or executor is needed. To give it a sandbox later, switch to `openai_hosted`.
- **Tools.** Web search runs on OpenAI's side. Five function tools run in this script against `state/desk.json`:
  - `get_desk_status`: time, focus, memories and check-ins
  - `set_focus`: records the task and optionally schedules a check-in
  - `save_memory`
  - `delete_memory`
  - `look_through_camera`: the terminal app has no camera, so it returns an error and Desku says so. The [GPT-Live engine](../server/README.md) answers it with a real photo from the phone.

  On `agent.session.requires_action`, the script retrieves the session, runs each pending `function_call` from `required_actions`, and posts an `agent.session.input.tool_result` with the same `turn_id` and `call_id`. Tool failures go back as `success: false` with an error message, so Desku can recover.
- **Streaming.**
  - `output_text.delta` is printed as Desku's reply.
  - Reasoning summaries and all other event types are printed dimmed to stderr.
  - The turn ends on `agent.session.turn.completed`.
  - `turn.failed`, `turn.cancelled`, `session.failed`, `environment.failed` and `error` events stop with a message and a non-zero exit.
  - If the stream closes early, the script prints the saved session status.
- **Errors and retries.** HTTP errors print as `code: message (param)`. Network failures, 429s and 5xx responses retry up to 4 times with backoff. Message and tool-result POSTs send an `Idempotency-Key`, so retries can't duplicate them.

## Verified

These steps were run against the live API on Oct 4, 2026 with `gpt-6-astra`:

1. Created the agent.
2. Started a session.
3. Desku called `set_focus`, got the result and confirmed.
4. In a follow-up, Desku made two tool calls in a row (`get_desk_status`, then `save_memory`) and answered from them.
5. `tick` delivered a due check-in and Desku asked how the work went.
6. A request with a bad session ID printed `HTTP 404 … not_found_error`.

Web search is configured but wasn't exercised in these runs.
