import { EventEmitter } from "node:events";
import WebSocket from "ws";
import type {
  ClientEvent,
  ServerEvent,
  SessionConfig,
} from "openai/resources/live/live";

export interface LiveClientOptions {
  url: string;
  apiKey: string;
  session: SessionConfig;
}

export interface LiveClientEvents {
  started: [sessionId: string];
  audio: [pcm16: Buffer];
  transcript: [role: "user" | "assistant", delta: string, startMs: number];
  delegation: [id: string];
  usage: [seconds: number];
  error: [message: string, code?: string];
  closed: [reason: string];
}

// GPT-Live caps each appended context message at 500 tokens. ~4 chars per token, with margin.
const MAX_APPEND_CHARS = 1800;

/**
 * One GPT-Live WebSocket session (wss://api.openai.com/v1/live/sessions), client-delegation
 * mode. Commands sent before session.started are queued and flushed once it arrives, as the
 * API requires.
 */
export class LiveClient extends EventEmitter<LiveClientEvents> {
  private ws: WebSocket | null = null;
  private started = false;
  private closed = false;
  private queue: ClientEvent[] = [];
  private seq = 0;
  sessionId: string | null = null;

  constructor(private readonly opts: LiveClientOptions) {
    super();
  }

  connect() {
    const ws = new WebSocket(this.opts.url, { headers: { Authorization: `Bearer ${this.opts.apiKey}` } });
    this.ws = ws;
    ws.on("open", () => {
      ws.send(JSON.stringify({ type: "session.start", event_id: this.nextId(), session: this.opts.session }));
    });
    ws.on("message", (data) => this.onMessage(data.toString()));
    ws.on("error", (err) => this.emit("error", err.message));
    ws.on("close", (code, reason) => this.finish(this.started ? "connection_lost" : `connect_failed:${code} ${reason}`));
  }

  get isOpen() {
    return !this.closed;
  }

  appendAudio(pcm16: Buffer) {
    // Before session.started, drop audio rather than queue it: stale speech is worse than none.
    if (!this.started || pcm16.length === 0 || pcm16.length % 2 !== 0) return;
    this.sendNow({ type: "session.input_audio.append", audio: pcm16.toString("base64") });
  }

  /** Something the model should say out loud. null = general context, not tied to a delegation. */
  commentary(content: string, delegationId: string | null) {
    this.send({ type: "session.commentary.append", content: clip(content), delegation_id: delegationId, event_id: this.nextId() });
  }

  /** Quiet context the model knows but doesn't say. */
  thinking(content: string, delegationId: string | null) {
    this.send({ type: "session.thinking.append", content: clip(content), delegation_id: delegationId, event_id: this.nextId() });
  }

  instructions(content: string) {
    this.send({ type: "session.instructions.append", content: clip(content), delegation_id: null, event_id: this.nextId() });
  }

  mute() {
    this.send({ type: "session.input_audio.mute", event_id: this.nextId() });
  }

  unmute() {
    this.send({ type: "session.input_audio.unmute", event_id: this.nextId() });
  }

  /** Graceful close; the socket stays open until session.closed arrives with final usage. */
  close() {
    if (this.closed) return;
    if (this.started) this.sendNow({ type: "session.close", event_id: this.nextId() });
    else this.ws?.close();
    // Don't hang forever if the server never confirms.
    setTimeout(() => this.ws?.terminate(), 5000).unref();
  }

  private send(event: ClientEvent) {
    if (this.closed) return;
    if (!this.started) this.queue.push(event);
    else this.sendNow(event);
  }

  private sendNow(event: ClientEvent) {
    if (this.ws?.readyState === WebSocket.OPEN) this.ws.send(JSON.stringify(event));
  }

  private onMessage(raw: string) {
    let event: ServerEvent;
    try {
      event = JSON.parse(raw);
    } catch {
      return;
    }
    switch (event.type) {
      case "session.started":
        this.started = true;
        this.sessionId = event.session.id;
        this.emit("started", event.session.id);
        for (const queued of this.queue.splice(0)) this.sendNow(queued);
        break;
      case "session.output_audio.delta":
        this.emit("audio", Buffer.from(event.delta, "base64"));
        break;
      case "session.input_transcript.delta":
        this.emit("transcript", "user", event.delta, event.start_ms);
        break;
      case "session.output_transcript.delta":
        this.emit("transcript", "assistant", event.delta, event.start_ms);
        break;
      case "session.delegation.created":
        if (event.delegation.target === "client") this.emit("delegation", event.delegation.id);
        break;
      case "session.usage.updated":
        this.emit("usage", event.usage.seconds);
        break;
      case "session.closed":
        this.finish(event.reason);
        this.ws?.close();
        break;
      case "error":
        this.emit("error", event.error.message, event.error.code ?? undefined);
        break;
    }
  }

  private finish(reason: string) {
    if (this.closed) return;
    this.closed = true;
    this.queue = [];
    this.emit("closed", reason);
  }

  private nextId() {
    return `desku_${++this.seq}`;
  }
}

function clip(s: string) {
  return s.length <= MAX_APPEND_CHARS ? s : `${s.slice(0, MAX_APPEND_CHARS - 1)}…`;
}
