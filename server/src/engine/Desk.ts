import type { WebSocket } from "ws";
import type { Apps } from "../apps/Apps.ts";
import type { Brain } from "../brain/AgentBrain.ts";
import type { Pages } from "../pages/Pages.ts";
import type { Config } from "../config.ts";
import type { LiveClient } from "../live/LiveClient.ts";
import { liveInstructions } from "../live/prompt.ts";
import { Transcript } from "../live/Transcript.ts";
import { mask, type PhoneCaller } from "../phone/AgentPhone.ts";
import type { Checkin, Store } from "../store/Store.ts";
import { renderToday } from "./today.ts";
import { type DeskAction, localTime, makeTools } from "./tools.ts";

/** What the Desk needs from a GPT-Live session; LiveClient in production, a fake in tests. */
export type Live = Pick<
  LiveClient,
  "connect" | "appendAudio" | "commentary" | "thinking" | "mute" | "unmute" | "close" | "on" | "isOpen"
>;
export type LiveFactory = (instructions: string) => Live;

export type DeviceMessage =
  | { type: "start" }
  | { type: "stop" }
  | { type: "mute" }
  | { type: "unmute" }
  | { type: "camera"; enabled: boolean }
  | { type: "photo"; requestId: string; image: string }
  | { type: "photo_error"; requestId: string; reason: string }
  | { type: "forget"; id: string }
  | { type: "forget_all" }
  /** A tap on the kiosk screen ("10 more minutes", "Show"), handled like something the user said. */
  | { type: "say"; text: string }
  /** The phone's always-on camera sees (or stops seeing) someone at the desk. */
  | { type: "presence"; present: boolean };

type LiveState = "idle" | "connecting" | "open";

/** Calling the user's phone when a check-in finds nobody at the desk. */
export interface AwayCalls {
  caller: PhoneCaller;
  /** The user's own phone, E.164. */
  toNumber: string;
  /** How long Desku waits for a spoken reply to a check-in before calling. */
  noReplySeconds: number;
  /** Local hours when Desku never calls, e.g. [22, 8]. */
  quietHours: [number, number] | null;
}

const PHOTO_TIMEOUT_MS = 20_000;
/** Back after this long away (per the camera) → Desku says welcome back and follows up. */
const WELCOME_AFTER_MS = 10 * 60_000;
const WELCOME_EVERY_MS = 30 * 60_000;
/** Not seen by the camera for this long when a check-in is due → phone them instead. */
const AWAY_BY_CAMERA_MS = 2 * 60_000;
const SORRY = "Sorry, I couldn't get to that just now. Can you try again in a moment?";

/**
 * The engine. One desk phone at a time connects over a WebSocket. Mic audio flows to a GPT-Live
 * voice session and its speech flows back. When GPT-Live delegates work, the brain (the Desku
 * agent session) handles it with tools and the answer goes back to GPT-Live to be spoken.
 * Check-ins come due on a timer and Desku speaks first: the proactive part.
 *
 * The voice session is opened on demand (wake word, Talk button, or a due check-in) and closed
 * after a quiet spell, since it's billed per minute. The brain session persists across them.
 */
export class Desk {
  private device: WebSocket | null = null;
  private live: Live | null = null;
  private liveState: LiveState = "idle";
  private transcript = new Transcript();
  private muted = false;
  private cameraOn = true;
  private busy = 0;
  private lastActivity = 0;
  private photoRequests = new Map<string, { resolve: (jpeg: string) => void; reject: (e: Error) => void }>();
  private timers: NodeJS.Timeout[] = [];
  private seq = 0;
  private away: AwayCalls | null = null;
  private awaitingReply: { checkin: Checkin; since: number } | null = null;
  private calling = false;
  private closeAfterReply = false;
  /** From the phone's on-device face detection; null until the phone reports it. */
  private presence: { present: boolean; since: number } | null = null;
  private lastWelcome = 0;
  /** Always-listening is paused after the user presses Stop, until they press Talk again. */
  private listenPaused = false;
  private lastOpenAt = 0;
  /** When Desku's voice last came through, and when we last told the phone to cut it off. */
  private lastVoiceAt = 0;
  private lastInterruptAt = 0;
  private lastTodayShownAt = 0;
  /** No "phone offline → call" right after startup: the phone needs a moment to reconnect. */
  private readonly startedAt: number;
  private apps: Apps | null = null;
  private pages: Pages | null = null;

  constructor(
    private readonly store: Store,
    private readonly brain: Brain,
    private readonly newLive: LiveFactory,
    private readonly config: Pick<Config, "idleCloseSeconds" | "timeZone"> & { alwaysListen?: boolean },
    private readonly now: () => number = Date.now,
    private readonly log: (msg: string) => void = console.log,
    private readonly settleMs = 400,
  ) {
    store.onChange(() => this.sendDesk());
    this.startedAt = now();
  }

  enablePages(pages: Pages) {
    this.pages = pages;
  }

  enableApps(apps: Apps) {
    this.apps = apps;
  }

  enableAwayCalls(away: AwayCalls) {
    this.away = away;
  }

  /** What's live right now, for the agent page. */
  status() {
    return {
      device: !!this.device,
      voice: this.liveState,
      muted: this.muted,
      camera: this.cameraOn,
      thinking: this.busy > 0,
      calling: this.calling,
      apps: !!this.apps,
      awayCalls: this.away && { to: mask(this.away.toNumber), afterSeconds: this.away.noReplySeconds, quietHours: this.away.quietHours },
    };
  }

  start(tickMs = 5_000) {
    this.timers.push(setInterval(() => this.tick(), tickMs));
  }

  stop() {
    this.timers.forEach(clearInterval);
    this.live?.close();
  }

  // ---------------------------------------------------------------- device

  attach(ws: WebSocket) {
    if (this.device) {
      this.log("desk: a new device connected; dropping the old one");
      this.device.close(4000, "replaced by another device");
    }
    this.device = ws;
    ws.on("message", (data, isBinary) => {
      if (this.device !== ws) return;
      if (isBinary) this.onMicAudio(data as Buffer);
      else this.onDeviceMessage(data.toString());
    });
    ws.on("close", () => {
      if (this.device !== ws) return;
      this.device = null;
      this.live?.close();
      for (const r of this.photoRequests.values()) r.reject(new Error("the desk phone disconnected"));
      this.photoRequests.clear();
    });
    this.sendState();
    this.sendDesk();
    this.listenPaused = false;
    if (this.config.alwaysListen) this.openLive();
    // Check-ins that came due while the phone was away get delivered now.
    void this.tick();
  }

  private onMicAudio(pcm: Buffer) {
    if (this.muted || !this.live || this.liveState !== "open") return;
    this.live.appendAudio(pcm);
  }

  private onDeviceMessage(raw: string) {
    let msg: DeviceMessage;
    try {
      msg = JSON.parse(raw);
    } catch {
      return this.send({ type: "error", message: "messages must be JSON" });
    }
    switch (msg.type) {
      case "start":
        this.listenPaused = false;
        return this.openLive();
      case "stop":
        this.listenPaused = true;
        return this.live?.close();
      case "mute":
        this.muted = true;
        this.live?.mute();
        return this.sendState();
      case "unmute":
        this.muted = false;
        this.live?.unmute();
        return this.sendState();
      case "camera":
        this.cameraOn = !!msg.enabled;
        return this.sendState();
      case "photo":
        this.photoRequests.get(msg.requestId)?.resolve(msg.image);
        this.photoRequests.delete(msg.requestId);
        return;
      case "photo_error":
        this.photoRequests.get(msg.requestId)?.reject(new Error(msg.reason || "the phone couldn't take a photo"));
        this.photoRequests.delete(msg.requestId);
        return;
      case "forget":
        this.store.deleteMemory(msg.id);
        return;
      case "forget_all":
        this.store.clearMemories();
        return;
      case "presence":
        void this.onPresence(!!msg.present);
        return;
      case "say":
        if (typeof msg.text === "string" && msg.text.trim()) void this.onTapped(msg.text.trim());
        return;
      default:
        this.send({ type: "error", message: `unknown message type ${(msg as { type: string }).type}` });
    }
  }

  // ---------------------------------------------------------------- voice session

  private openLive() {
    if (this.live?.isOpen) return;
    this.closeAfterReply = false;
    this.lastOpenAt = this.now();
    this.transcript = new Transcript();
    this.lastActivity = this.now();
    const live = this.newLive(liveInstructions(this.store, this.config.timeZone, this.now()));
    this.live = live;
    this.liveState = "connecting";
    this.sendState();

    live.on("started", (id) => {
      this.log(`live: session ${id} started`);
      this.liveState = "open";
      if (this.muted) live.mute();
      this.sendState();
    });
    live.on("audio", (pcm) => {
      this.lastActivity = this.now();
      this.lastVoiceAt = this.now();
      if (this.device?.readyState === 1) this.device.send(pcm, { binary: true });
    });
    live.on("transcript", (role, delta, startMs) => {
      this.lastActivity = this.now();
      if (role === "user") {
        this.awaitingReply = null; // someone's at the desk
        // Barge-in: GPT-Live already stops generating when the user talks over it, but the phone
        // has a few seconds of Desku's speech queued. Tell it to drop that right away.
        const now = this.now();
        if (now - this.lastVoiceAt < 3_000 && now - this.lastInterruptAt > 1_000 && delta.trim()) {
          this.lastInterruptAt = now;
          this.send({ type: "interrupt" });
        }
      }
      this.transcript.add(role, delta, startMs);
      this.send({ type: "transcript", role, delta });
    });
    live.on("delegation", (id) => void this.onDelegation(live, id));
    live.on("error", (message, code) => {
      this.log(`live: error ${code ?? ""} ${message}`);
      this.send({ type: "error", message: `voice: ${message}` });
    });
    live.on("closed", (reason) => {
      this.log(`live: closed (${reason})`);
      if (this.live === live) {
        this.live = null;
        this.liveState = "idle";
        this.sendState();
      }
    });
    live.connect();
  }

  private async onDelegation(live: Live, delegationId: string) {
    this.busy++;
    this.sendState();
    try {
      // The delegation event carries no text. Give the last transcript fragments a moment
      // to land, then take what the user said since the previous delegation.
      await sleep(this.settleMs);
      const heard = this.transcript.takeUserRequest();
      const previous = this.transcript.previousAssistant();
      const context = this.transcript.recent(8);
      this.log(`desk: delegation ${delegationId}: "${heard}"`);
      const message =
        `[Heard] ${heard || "(nothing transcribed)"}` + (context ? `\n\nRecent voice conversation, for context:\n${context}` : "");
      const answer = await this.brain.ask(message, this.tools(heard, previous), (progress, pending) => {
        // Speak it only when the user has to do something (hold the page up); otherwise it's
        // a "saving that now" the voice already covers with its own filler.
        if (pending.includes("look_through_camera")) live.commentary(progress.trim(), delegationId);
        else live.thinking(progress.trim(), delegationId);
      });
      live.commentary(answer.trim() || "Done.", delegationId);
    } catch (e) {
      this.log(`desk: brain failed: ${(e as Error).message}`);
      this.send({ type: "error", message: `brain: ${(e as Error).message}` });
      live.commentary(SORRY, delegationId);
    } finally {
      this.busy--;
      this.lastActivity = this.now();
      this.sendState();
    }
  }

  /**
   * Presence from the phone's camera. Coming back after a while is a moment to notice: Desku
   * greets the user and follows up on what they were working on.
   */
  private async onPresence(present: boolean) {
    const now = this.now();
    const before = this.presence;
    if (before && before.present === present) return;
    this.presence = { present, since: now };
    // Seen arriving (or first seen after connecting): put the day on the screen.
    if (present && (!before || now - before.since > 60_000) && now - this.lastTodayShownAt > 2 * 60_000) this.showToday();
    if (!present || !before) return;
    this.awaitingReply = null;
    const awayMs = now - before.since;
    const focus = this.store.focus();
    const hour = Number(new Intl.DateTimeFormat("en-US", { timeZone: this.config.timeZone, hour: "numeric", hourCycle: "h23" }).format(now));
    const quiet = this.away?.quietHours && inQuietHours(hour, this.away.quietHours);
    if (awayMs < WELCOME_AFTER_MS || !focus || this.live || this.busy > 0 || now - this.lastWelcome < WELCOME_EVERY_MS || quiet) return;
    this.lastWelcome = now;
    this.log(`desk: user back after ${Math.round(awayMs / 60_000)} min; following up`);
    this.openLive();
    const live = this.live!;
    this.busy++;
    try {
      const answer = await this.brain.ask(
        `[Desk event] The camera shows the user just came back to the desk after ${Math.round(awayMs / 60_000)} minutes away. Their focus was: ${focus.task}. Welcome them back in one short sentence and ask one question about that focus.`,
        this.tools("", null),
      );
      if (live.isOpen) live.commentary(answer.trim(), null);
    } catch (e) {
      this.log(`desk: welcome back failed: ${(e as Error).message}`);
    } finally {
      this.busy--;
      this.lastActivity = this.now();
    }
  }

  /** Puts the Today screen (to-dos, focus, memories) on the phone, replacing the last one. */
  showToday(): void {
    if (!this.pages || !this.device) return;
    try {
      const page = this.pages.save("Today", renderToday(this.store, this.config.timeZone, this.now()), this.store.todayPageId() || null);
      if (page.id !== this.store.todayPageId()) this.store.setTodayPageId(page.id);
      this.lastTodayShownAt = this.now();
      this.send({ type: "page", id: page.id, title: page.title, url: `/p/${page.id}?v=${page.updatedAt}` });
    } catch (e) {
      // The saved page may have been deleted from the agent page: start a fresh one.
      if (this.store.todayPageId()) {
        this.store.setTodayPageId("");
        return this.showToday();
      }
      this.log(`desk: today page failed: ${(e as Error).message}`);
    }
  }

  /** Screen taps don't go through GPT-Live's ears, so they go straight to the brain. */
  private async onTapped(text: string) {
    this.openLive();
    const live = this.live!;
    this.awaitingReply = null;
    this.busy++;
    this.sendState();
    try {
      this.transcript.note("user", ` ${text}`);
      const answer = await this.brain.ask(
        `[Tapped on the desk screen] ${text}`,
        this.tools(text, this.transcript.previousAssistant()),
        (progress, pending) => {
          if (pending.includes("look_through_camera")) live.commentary(progress.trim(), null);
        },
      );
      if (live.isOpen) live.commentary(answer.trim() || "Done.", null);
    } catch (e) {
      this.send({ type: "error", message: `brain: ${(e as Error).message}` });
      if (live.isOpen) live.commentary(SORRY, null);
    } finally {
      this.busy--;
      this.lastActivity = this.now();
      this.sendState();
    }
  }

  private tools(userWords: string, previousAssistant: string | null) {
    return makeTools({
      store: this.store,
      timeZone: this.config.timeZone,
      now: this.now,
      userWords,
      recentUserWords: userWords ? this.transcript.recentUser(4) : "",
      previousAssistant,
      cameraOn: () => this.cameraOn && !!this.device,
      takePhoto: () => this.takePhoto(),
      apps: this.apps,
      placeCall: ({ to, reason, callerName }) => {
        if (!this.away) return "Phone calls aren't set up yet: the user's own number (OWNER_PHONE) and the AgentPhone key go in server/.env.";
        if (this.calling) return "A call is already in progress.";
        const now = this.now();
        if (!to || to === this.away.toNumber) {
          void this.callAway({ id: `call${now}`, dueAt: now, createdAt: now, about: reason }, "the user asked Desku to call them", true);
        } else {
          void this.callForUser(to, reason, callerName);
        }
        return null;
      },
      control: (action) => this.control(action),
      presence: () =>
        this.presence
          ? { atDesk: this.presence.present, minutes: Math.floor((this.now() - this.presence.since) / 60_000) }
          : { atDesk: null, minutes: 0 },
      pages: this.pages,
      showToday: () => this.showToday(),
      onPage: (page) => {
        this.log(`desk: page ${page.id} "${page.title}"`);
        // ?v= changes on every update, so the phone reloads a page that kept its id.
        this.send({ type: "page", id: page.id, title: page.title, url: `/p/${page.id}?v=${page.updatedAt}` });
      },
      onLink: (url) => {
        this.log(`desk: connect link: ${url}`);
        this.send({ type: "link", url, label: "Connect app" });
      },
    });
  }

  private takePhoto(): Promise<string> {
    if (!this.device) return Promise.reject(new Error("the desk phone isn't connected"));
    const requestId = `p${++this.seq}`;
    return new Promise<string>((resolve, reject) => {
      const timer = setTimeout(() => {
        this.photoRequests.delete(requestId);
        reject(new Error("the phone didn't send a photo in time"));
      }, PHOTO_TIMEOUT_MS);
      this.photoRequests.set(requestId, {
        resolve: (jpeg) => (clearTimeout(timer), resolve(jpeg)),
        reject: (e) => (clearTimeout(timer), reject(e)),
      });
      // No countdown: the phone's always-on camera glances and the view closes by itself.
      this.send({ type: "capture", requestId, countdownSeconds: 0 });
    });
  }

  // ---------------------------------------------------------------- proactive check-ins + idle

  async tick() {
    const now = this.now();
    if (this.closeAfterReply && this.live && this.busy === 0 && now - this.lastActivity > 3_000) {
      this.closeAfterReply = false;
      this.live.close();
    }
    // Always listening: reopen a dropped or expired voice session (at most every 10 s).
    if (this.config.alwaysListen && this.device && !this.live && !this.listenPaused && !this.calling && now - this.lastOpenAt > 10_000) {
      this.openLive();
    }
    if (!this.config.alwaysListen && this.live && this.liveState === "open" && this.busy === 0 && now - this.lastActivity > this.config.idleCloseSeconds * 1000) {
      this.log("desk: voice session idle; closing it");
      this.live.close();
    }
    if (this.awaitingReply && now - this.awaitingReply.since > (this.away?.noReplySeconds ?? Infinity) * 1000) {
      const { checkin } = this.awaitingReply;
      this.awaitingReply = null;
      this.live?.close();
      void this.callAway(checkin, "Desku asked at the desk and nobody answered");
    }
    const due = this.store.pendingCheckins().filter((c) => c.dueAt <= now);
    if (!this.device) {
      // With phone calls set up, an offline desk means call; otherwise wait for the phone.
      if (!this.away || now - this.startedAt < 2 * 60_000) return;
      for (const checkin of due) {
        this.store.markCheckinFired(checkin.id);
        void this.callAway(checkin, "the desk phone is offline");
      }
      return;
    }
    const goneFor = this.presence && !this.presence.present ? now - this.presence.since : 0;
    for (const checkin of due) {
      this.store.markCheckinFired(checkin.id);
      if (this.away && goneFor > AWAY_BY_CAMERA_MS) void this.callAway(checkin, "the desk camera hasn't seen them for a while");
      else await this.deliverCheckin(checkin);
    }
  }

  private async deliverCheckin(checkin: Checkin) {
    this.log(`desk: check-in due: ${checkin.about}`);
    this.send({ type: "checkin", about: checkin.about });
    this.openLive();
    const live = this.live!;
    this.busy++;
    try {
      const late = Math.round((this.now() - checkin.dueAt) / 60_000);
      const answer = await this.brain.ask(
        `[Desk event] Check-in due${late > 2 ? ` (${late} minutes late; the phone was offline)` : ""}: ${checkin.about}. Check in with the user now, in one or two sentences.`,
        this.tools("", null),
      );
      // Not tied to a delegation: GPT-Live speaks it as a new turn.
      if (live.isOpen) live.commentary(answer.trim(), null);
      if (this.away) this.awaitingReply = { checkin, since: this.now() };
    } catch (e) {
      this.log(`desk: check-in failed: ${(e as Error).message}`);
      if (live.isOpen) live.commentary(`Quick check-in: ${checkin.about.replace(/^Focus check-in: /, "")}. How's it going?`, null);
    } finally {
      this.busy--;
      this.lastActivity = this.now();
    }
  }

  /**
   * The user isn't at the desk: call their phone from Desku's AgentPhone number. AgentPhone's
   * hosted voice AI runs the call; afterwards the transcript goes to the brain so focus and
   * check-ins reflect what was said.
   */
  async callAway(checkin: Checkin, why: string, requested = false) {
    const away = this.away;
    if (!away || this.calling) return;
    const hour = Number(new Intl.DateTimeFormat("en-US", { timeZone: this.config.timeZone, hour: "numeric", hourCycle: "h23" }).format(this.now()));
    if (!requested && away.quietHours && inQuietHours(hour, away.quietHours)) {
      this.log(`desk: ${why}, but it's quiet hours; not calling`);
      return;
    }
    this.calling = true;
    const task = checkin.about.replace(/^Focus check-in: /, "");
    this.log(`desk: ${why}; calling ${mask(away.toNumber)} about "${task}"`);
    this.send({ type: "calling", about: checkin.about });
    try {
      const memories = this.store.memories().map((m) => `- ${m.text}`).join("\n");
      const result = await away.caller.call({
        toNumber: away.toNumber,
        initialGreeting: requested
          ? `Hi, it's Desku, your AI desk buddy, calling like you asked. What's up?`
          : `Hi, it's Desku, your AI desk buddy. You're away from your desk, so I'm calling for a quick check-in on ${task}. How's it going?`,
        systemPrompt: `You are Desku, the user's AI desk buddy, which normally lives in a phone on their desk. You are calling the user's own phone because ${requested ? `${why}. Topic: ${checkin.about}` : `a check-in came due and ${why}. Check-in: ${checkin.about}`}. It is ${localTime(this.now(), this.config.timeZone)}.
${memories ? `Things the user asked you to remember:\n${memories}\n` : ""}
Goal: a friendly check-in of under a minute. Ask how it's going and whether they want to keep going, take a break or switch tasks, and when they'll be back at the desk. If they ask you to remember something or change their plan, say you'll update it when you hang up. If they're busy or it's a bad time, apologise briefly and end the call. Speak in short sentences. Never pretend to be human. Say goodbye and end the call when done.`,
      });
      const lines = result.transcript.map((t) => `${t.role === "user" ? "User" : "Desku"}: ${t.content}`).join("\n");
      const userWords = result.transcript.filter((t) => t.role === "user").map((t) => t.content).join(" ");
      if (!lines) {
        this.log(`desk: call ${result.id} ${result.status} with no conversation (missed or voicemail)`);
        return;
      }
      await this.brain.ask(
        `[Desk event] The user was away, so you phoned them about "${checkin.about}". Call transcript:\n${lines}\n\nUpdate the focus or schedule a check-in if they asked; save a memory only if they explicitly asked you to remember something. Reply with one sentence summarising the call for the desk screen.`,
        this.tools(userWords, null),
      );
    } catch (e) {
      this.log(`desk: phone call failed: ${(e as Error).message}`);
      this.send({ type: "error", message: `phone call: ${(e as Error).message}` });
      if (checkin.id.startsWith("call")) this.reportToUser(callProblem(e as Error));
    } finally {
      this.calling = false;
    }
  }

  /**
   * Calls someone else for the user (a friend, say), then tells the user how it went. The
   * callee hears up front that it's an AI calling on the user's behalf.
   */
  private async callForUser(to: string, reason: string, callerName: string | null) {
    const away = this.away;
    if (!away || this.calling) return;
    this.calling = true;
    const who = callerName ? callerName : "someone who knows you";
    this.log(`desk: calling ${mask(to)} for the user about "${reason}"`);
    this.send({ type: "calling", about: `Calling ${to}: ${reason}` });
    try {
      const result = await away.caller.call({
        toNumber: to,
        initialGreeting: `Hi, this is Desku, an AI assistant calling on behalf of ${who}. Is now an okay time for a quick message?`,
        systemPrompt: `You are Desku, an AI assistant calling on behalf of ${who}, who asked you to make this call. Purpose, in their words: ${reason}
Say early on that you're an AI assistant calling for them. Deliver the purpose briefly and politely, and listen to the reply so you can report it back. If they ask, you may take a short message or a call-back time. Don't share anything about ${who} beyond the purpose. If it's a bad time or they don't want to talk, apologise and end the call. Never pretend to be human. Keep it under two minutes, then say goodbye and end the call.`,
      });
      const lines = result.transcript.map((t) => `${t.role === "user" ? "Them" : "Desku"}: ${t.content}`).join("\n");
      const report = await this.brain.ask(
        `[Desk event] You phoned ${to} for the user about "${reason}". Call status: ${result.status}.${lines ? ` Transcript:\n${lines}` : " Nobody answered (or it went to voicemail)."}\n\nTell the user in one or two sentences how it went and anything the person said back. Don't save memories from this call.`,
        this.tools("", null),
      );
      this.reportToUser(report);
    } catch (e) {
      this.log(`desk: call to ${mask(to)} failed: ${(e as Error).message}`);
      this.send({ type: "error", message: `phone call: ${(e as Error).message}` });
      this.reportToUser(callProblem(e as Error));
    } finally {
      this.calling = false;
    }
  }

  /** Says it if a voice session is open; otherwise opens one when the phone is there. */
  private reportToUser(text: string) {
    if (!text.trim() || !this.device) return;
    this.openLive();
    this.live?.commentary(text.trim(), null);
    this.lastActivity = this.now();
  }

  /** Desk controls the agent can use (the user can always undo them on the screen). */
  private control(action: DeskAction): string {
    switch (action) {
      case "camera_on":
        this.cameraOn = true;
        break;
      case "camera_off":
        this.cameraOn = false;
        break;
      case "mute_mic":
        this.muted = true;
        this.live?.mute();
        break;
      case "end_conversation":
        this.closeAfterReply = true;
        break;
    }
    this.send({ type: "control", action });
    this.sendState();
    return {
      camera_on: "Camera switched on.",
      camera_off: "Camera switched off.",
      close_camera: "Camera screen closed.",
      mute_mic: "Mic muted. The user can unmute it on the screen; you won't hear them until then.",
      show_memories: "Memories are on the screen.",
      hide_memories: "Memories screen closed.",
      end_conversation: "The conversation will end after your reply. Say a short goodbye.",
    }[action];
  }

  // ---------------------------------------------------------------- to the phone

  private send(msg: Record<string, unknown>) {
    if (this.device?.readyState === 1) this.device.send(JSON.stringify(msg));
  }

  private sendState() {
    this.send({ type: "state", live: this.liveState, muted: this.muted, camera: this.cameraOn, thinking: this.busy > 0 });
  }

  private sendDesk() {
    this.send({
      type: "desk",
      memories: this.store.memories(),
      focus: this.store.focus(),
      checkins: this.store.pendingCheckins(),
    });
  }
}

/** A phone failure in words Desku can say out loud. */
function callProblem(e: Error) {
  if (/→ 402/.test(e.message)) return "I couldn't place the call: my phone line has no credit yet. Add funds on the AgentPhone billing page and I'll try again.";
  if (/→ 40[13]/.test(e.message)) return "I couldn't place the call: my phone line's key isn't working.";
  return "I couldn't get the call through just now. Want me to try again?";
}

function inQuietHours(hour: number, [start, end]: [number, number]) {
  return start <= end ? hour >= start && hour < end : hour >= start || hour < end;
}

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));
