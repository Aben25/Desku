#!/usr/bin/env bash
# Desku on the OpenAI Agents API, driven with curl + jq.
#
#   ./desku.sh create-agent [--force]   save the reusable "Desku" agent, remember its ID
#   ./desku.sh start ["message"]        new session from the saved agent, stream the first turn
#   ./desku.sh say "message"            send a follow-up to the current session and stream it
#   ./desku.sh chat                     interactive loop on the current session
#   ./desku.sh tick                     deliver due check-ins to the session (run from cron)
#   ./desku.sh status | items | cancel | end
#
# Function tools (get_desk_status, set_focus, save_memory, delete_memory) run here, against
# state/desk.json. Credentials come from OPENAI_API_KEY (or agent/.env, which is gitignored).
set -euo pipefail

DIR=$(cd "$(dirname "$0")" && pwd)
if [ -f "$DIR/.env" ]; then set -a; . "$DIR/.env"; set +a; fi
: "${OPENAI_API_KEY:?Set OPENAI_API_KEY or create agent/.env from .env.example}"
API=${OPENAI_BASE_URL:-https://api.openai.com/v1}
PROJECT=${OPENAI_PROJECT:-proj_2apst5kE7CvBS51WxiS1MWax}
TZ_NAME=${DESKU_TZ:-}
STATE=$DIR/state
DB=$STATE/desk.json
mkdir -p "$STATE"
[ -f "$DB" ] || echo '{"memories":[],"focus":null,"checkins":[]}' > "$DB"

if [ -t 1 ]; then DIM=$'\033[2m'; RED=$'\033[31m'; BOLD=$'\033[1m'; OFF=$'\033[0m'; else DIM= RED= BOLD= OFF=; fi
log()  { printf '%s%s%s\n' "$DIM" "$*" "$OFF" >&2; }
fail() { printf '%sError:%s %s\n' "$RED" "$OFF" "$*" >&2; }

HEADERS=(
  -H "Authorization: Bearer $OPENAI_API_KEY"
  -H "OpenAI-Beta: agents=v1"
  -H "OpenAI-Project: $PROJECT"
  -H "Content-Type: application/json"
)

# Turn an API error body into one readable line: "code: message (param)".
explain_error() {
  jq -r '.error // . | if type == "object"
    then "\(.code // .type // "error"): \(.message // tostring)\(if .param then " (param: \(.param))" else "" end)"
    else tostring end' <<<"$1" 2>/dev/null || printf '%s\n' "$1"
}

# api METHOD PATH [BODY] [IDEMPOTENCY_KEY] -> prints the response body.
# Retries 429/5xx and network failures with backoff; the idempotency key makes retried POSTs safe.
api() {
  local method=$1 path=$2 body=${3:-} key=${4:-} attempt=0 out code
  local args=(-sS -X "$method" "$API$path" "${HEADERS[@]}" -w $'\n%{http_code}')
  [ -n "$body" ] && args+=(--data "$body")
  [ -n "$key" ] && args+=(-H "Idempotency-Key: $key")
  while :; do
    if out=$(curl "${args[@]}"); then
      code=${out##*$'\n'}; out=${out%$'\n'*}
    else
      code=000; out='{"error":{"code":"network_error","message":"could not reach the API"}}'
    fi
    case $code in
      2??) printf '%s' "$out"; return 0 ;;
      000|429|500|502|503|504)
        attempt=$((attempt + 1))
        if [ $attempt -le 4 ]; then
          log "HTTP $code ($(explain_error "$out")), retrying in $((2 ** attempt))s"
          sleep $((2 ** attempt)); continue
        fi ;;
    esac
    fail "HTTP $code on $method $path: $(explain_error "$out")"
    return 1
  done
}

new_key() { uuidgen | tr 'A-Z' 'a-z'; }
session_id() {
  [ -s "$STATE/session_id" ] || { fail "No session yet. Run: ./desku.sh start"; exit 1; }
  cat "$STATE/session_id"
}

# ---------------------------------------------------------------- function tools

now_text() { if [ -n "$TZ_NAME" ]; then TZ=$TZ_NAME date '+%A %B %-d, %-I:%M %p %Z'; else date '+%A %B %-d, %-I:%M %p %Z'; fi; }

db_update() { local tmp; tmp=$(mktemp); jq "$@" "$DB" > "$tmp" && mv "$tmp" "$DB"; }

# run_tool NAME ARGS_JSON -> prints the result string; non-zero exit = tool error message.
run_tool() {
  local name=$1 args=$2 now; now=$(date +%s)
  case $name in
    get_desk_status)
      jq -c --arg time "$(now_text)" --argjson now "$now" '{
        local_time: $time,
        focus: (.focus | if . then {task, minutes_ago: ((($now - .started_at) / 60) | floor)} else null end),
        memories: [.memories[] | {id, text}],
        due_checkins: [.checkins[] | select(.due_at <= $now) | .about],
        upcoming_checkins: [.checkins[] | select(.due_at > $now) | {about, in_minutes: (((.due_at - $now) / 60) | ceil)}]
      }' "$DB" ;;
    set_focus)
      local task minutes
      task=$(jq -r '.task // empty' <<<"$args"); minutes=$(jq -r '.checkin_minutes // empty' <<<"$args")
      [ -n "$task" ] || { echo "task is required"; return 1; }
      db_update --arg task "$task" --argjson now "$now" --argjson min "${minutes:-null}" '
        .focus = {task: $task, started_at: $now}
        | .checkins = ([.checkins[] | select(.about | startswith("Focus check-in") | not)]
            + (if $min then [{id: "c\($now)", due_at: ($now + $min * 60), about: "Focus check-in: \($task)"}] else [] end))'
      if [ -n "$minutes" ]; then echo "Focus set. Check-in scheduled in $minutes minutes."; else echo "Focus set. No check-in scheduled."; fi ;;
    save_memory)
      local text; text=$(jq -r '.text // empty' <<<"$args")
      [ -n "$text" ] || { echo "text is required"; return 1; }
      db_update --arg text "$text" --argjson now "$now" '
        .memories += [{id: ("m" + (([.memories[].id | ltrimstr("m") | tonumber] | max // 0) + 1 | tostring)), text: $text, created_at: $now}]'
      jq -r '"Saved as \(.memories[-1].id)."' "$DB" ;;
    delete_memory)
      local id; id=$(jq -r '.id // empty' <<<"$args")
      jq -e --arg id "$id" 'any(.memories[]; .id == $id)' "$DB" >/dev/null || { echo "No memory with id $id"; return 1; }
      db_update --arg id "$id" '.memories |= map(select(.id != $id))'
      echo "Deleted $id." ;;
    call_phone|control_desk|show_page) echo "Phone calls, desk controls and pages run through the Desku server in server/, not this terminal app."; return 1 ;;
    COMPOSIO_*) echo "Apps (Composio) run through the Desku server in server/, not this terminal app."; return 1 ;;
    look_through_camera) echo "No camera in the terminal app. Ask the user to describe or paste it instead."; return 1 ;;
    *) echo "Unknown function $name"; return 1 ;;
  esac
}

# Answer every pending function call on the session. Called on agent.session.requires_action.
handle_required_actions() {
  local sid=$1 session actions n i action name args call_id turn_id result ok event
  session=$(api GET "/agents/sessions/$sid") || return 1
  actions=$(jq -c '[.required_actions // [] | .[] | select(.type == "function_call")]' <<<"$session")
  n=$(jq length <<<"$actions")
  if [ "$n" = 0 ]; then
    fail "Session needs an action this app doesn't handle: $(jq -c '.required_actions' <<<"$session")"
    return 1
  fi
  for ((i = 0; i < n; i++)); do
    action=$(jq -c ".[$i]" <<<"$actions")
    name=$(jq -r .name <<<"$action"); call_id=$(jq -r .call_id <<<"$action"); turn_id=$(jq -r .turn_id <<<"$action")
    grep -qx "$call_id" "$STATE/answered_calls" 2>/dev/null && continue
    # arguments arrive as an object; accept a JSON string too.
    args=$(jq -c '.arguments | if type == "string" then (fromjson? // {}) else (. // {}) end' <<<"$action")
    log "→ tool $name $args"
    if result=$(run_tool "$name" "$args" 2>&1); then ok=true; else ok=false; fi
    log "← $result"
    event=$(jq -nc --arg call "$call_id" --arg turn "$turn_id" --arg res "$result" --argjson ok $ok '{events: [{
      type: "agent.session.input.tool_result", call_id: $call, turn_id: $turn, success: $ok,
      output: (if $ok then $res else null end), error: (if $ok then null else $res end)}]}')
    api POST "/agents/sessions/$sid/events" "$event" "tool-$call_id" >/dev/null || return 1
    echo "$call_id" >> "$STATE/answered_calls"
  done
}

# ---------------------------------------------------------------- streaming

# Read server-sent events from fd 3 until the turn ends. Prints the agent's text to stdout and
# everything else, dimmed, to stderr. Returns 0 only for agent.session.turn.completed.
follow_stream() {
  local line data type sid printed=0
  while IFS= read -r line <&3 || [ -n "$line" ]; do
    line=${line%$'\r'}
    case $line in
      data:*) data=${line#data:}; data=${data# } ;;
      '{'*) fail "$(explain_error "$line")"; return 1 ;;  # HTTP error body instead of a stream
      *) continue ;;
    esac
    [ "$data" = "[DONE]" ] && break
    type=$(jq -r '.type // empty' <<<"$data" 2>/dev/null) || continue

    if [ ! -s "$STATE/session_id" ]; then
      sid=$(jq -r '.session.id // .session_id // empty' <<<"$data")
      if [ -n "$sid" ]; then echo "$sid" > "$STATE/session_id"; log "session $sid"; fi
    fi

    case $type in
      agent.session.turn.output_text.delta)
        [ $printed = 0 ] && printf '%sDesku:%s ' "$BOLD" "$OFF"
        printf '%s' "$(jq -r .delta <<<"$data")"; printed=1 ;;
      agent.session.turn.output_text.done)
        if [ $printed = 0 ]; then printf '%sDesku:%s %s' "$BOLD" "$OFF" "$(jq -r .text <<<"$data")"; fi
        printf '\n'; printed=0 ;;
      *reasoning_summary*delta) printf '%s%s%s' "$DIM" "$(jq -r .delta <<<"$data")" "$OFF" >&2 ;;
      *reasoning_summary*done) printf '\n' >&2 ;;
      agent.session.requires_action)
        log "[$type]"
        handle_required_actions "$(session_id)" || return 1 ;;
      agent.session.turn.completed)
        log "[turn completed]"; return 0 ;;
      agent.session.turn.failed|agent.session.turn.cancelled)
        fail "$type: $(jq -c '.turn.error // .error // .turn.status // .' <<<"$data")"; return 1 ;;
      agent.session.failed|agent.session.environment.failed)
        fail "$type: $(jq -c '.session.error // .environment.error // .error // .' <<<"$data")"; return 1 ;;
      error)
        fail "stream error: $(explain_error "$data")"; return 1 ;;
      *) log "[$type]" ;;
    esac
  done
  # The stream ended without a turn outcome. Saved state says what actually happened.
  fail "Stream closed before the turn finished."
  if [ -s "$STATE/session_id" ]; then
    log "Session status: $(api GET "/agents/sessions/$(cat "$STATE/session_id")" | jq -c '{status, error, required_actions}')"
  fi
  return 1
}

# ---------------------------------------------------------------- commands

create_agent() {
  if [ "${1:-}" != "--force" ] && [ -s "$STATE/agent_id" ]; then
    local id; id=$(cat "$STATE/agent_id")
    if api GET "/agents/$id" >/dev/null 2>&1; then log "Using saved agent $id (--force to recreate)"; echo "$id"; return; fi
    log "Saved agent $id is gone; creating a new one."
  fi
  local agent
  agent=$(api POST /agents "$(jq -c . "$DIR/desku-agent.json")") || exit 1
  jq -r .id <<<"$agent" > "$STATE/agent_id"
  log "Created agent $(jq -r '"\(.id) (\(.name), \(.model))"' <<<"$agent")"
  cat "$STATE/agent_id"
}

start_session() {
  local message=${1:-"Hi Desku, I just sat down at my desk."} agent_id body
  agent_id=$(create_agent)
  rm -f "$STATE/session_id"
  printf '%sYou:%s %s\n' "$BOLD" "$OFF" "$message"
  # Environment "none": Desku talks, searches and calls our functions; it needs no sandbox.
  body=$(jq -nc --arg agent "$agent_id" --arg msg "$message" '{
    agent_id: $agent, environment: {type: "none"}, input: $msg, stream: true, metadata: {app: "desku"}}')
  exec 3< <(curl -sS -N -X POST "$API/agents/sessions" "${HEADERS[@]}" -H "Accept: text/event-stream" --data "$body" 2>"$STATE/stream.err")
  run_stream
}

# follow_stream, then close the stream. curl's own errors are shown only if the turn didn't
# complete; on success they're just "we hung up first" noise.
run_stream() {
  local rc=0
  follow_stream || rc=$?
  exec 3<&-
  if [ $rc != 0 ] && [ -s "$STATE/stream.err" ]; then log "curl: $(cat "$STATE/stream.err")"; fi
  return $rc
}

say() {
  local message=$1 sid body
  sid=$(session_id)
  printf '%sYou:%s %s\n' "$BOLD" "$OFF" "$message"
  # Subscribe first so the turn's early events aren't missed, then send.
  exec 3< <(curl -sS -N "$API/agents/sessions/$sid/events?stream=true" "${HEADERS[@]}" -H "Accept: text/event-stream" 2>"$STATE/stream.err")
  sleep 1
  body=$(jq -nc --arg msg "$message" '{events: [{type: "agent.session.input.message",
    input: [{role: "user", content: [{type: "input_text", text: $msg}]}]}]}')
  api POST "/agents/sessions/$sid/events" "$body" "$(new_key)" >/dev/null || { exec 3<&-; return 1; }
  run_stream
}

case ${1:-help} in
  create-agent) create_agent "${2:-}" ;;
  start) start_session "${2:-}" ;;
  say) [ -n "${2:-}" ] || { fail 'Usage: ./desku.sh say "message"'; exit 1; }; say "$2" ;;
  chat)
    [ -s "$STATE/session_id" ] || start_session
    while IFS= read -r -p "> " msg; do [ -n "$msg" ] && { say "$msg" || true; }; done ;;
  tick)
    now=$(date +%s)
    due=$(jq -r --argjson now "$now" '[.checkins[] | select(.due_at <= $now) | .about] | join("; ")' "$DB")
    [ -n "$due" ] || { log "No check-ins due."; exit 0; }
    db_update --argjson now "$now" '.checkins |= map(select(.due_at > $now))'
    say "[Desk event, not typed by the user] Check-in due: $due. Check in with the user now." ;;
  status) api GET "/agents/sessions/$(session_id)" | jq '{id, status, error, required_actions}' ;;
  items) api GET "/agents/sessions/$(session_id)/items?order=asc&limit=100" | jq '.data' ;;
  cancel) api POST "/agents/sessions/$(session_id)/events" '{"events":[{"type":"agent.session.input.cancel"}]}' "$(new_key)" | jq . ;;
  end) api DELETE "/agents/sessions/$(session_id)" >/dev/null && rm -f "$STATE/session_id" && log "Session deleted." ;;
  *) sed -n '2,12p' "$0" | sed 's/^# \{0,1\}//' ;;
esac
