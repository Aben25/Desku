import type { InputContentParam } from "openai/resources/beta/agents/agents";
import type { Apps } from "../apps/Apps.ts";
import type { Page, Pages } from "../pages/Pages.ts";
import { allowsSave } from "../brain/MemoryPolicy.ts";
import type { Store } from "../store/Store.ts";

export type ToolResult = { ok: true; output: string | InputContentParam[] } | { ok: false; error: string };
export type ToolRunner = (name: string, args: Record<string, unknown>) => Promise<ToolResult>;

export interface ToolContext {
  store: Store;
  timeZone: string;
  now: () => number;
  /** What the user said that led to this brain turn; empty for app-initiated turns. */
  userWords: string;
  previousAssistant: string | null;
  cameraOn: () => boolean;
  /** Asks the phone for one photo (with countdown). Resolves to base64 JPEG. */
  takePhoto: () => Promise<string>;
  /** The user's connected apps via Composio, if configured. */
  apps?: Apps | null;
  /** A Connect Link the user must open; shown on the desk screen. */
  onLink?: (url: string) => void;
  /** Starts a phone call (to the user when `to` is null). Returns an error message, or null once placed. */
  placeCall?: (call: { to: string | null; reason: string; callerName: string | null }) => string | null;
  /** Desk controls the agent may use: camera, mic, memories screen, ending the conversation. */
  control?: (action: DeskAction) => string;
  /** Web pages Desku writes for the desk screen, and where to announce a new or updated one. */
  pages?: Pages | null;
  /** Whether the always-on camera sees someone at the desk (null = unknown), and for how long. */
  presence?: () => { atDesk: boolean | null; minutes: number };
  onPage?: (page: Page) => void;
}

export const DESK_ACTIONS = ["camera_on", "camera_off", "close_camera", "mute_mic", "show_memories", "hide_memories", "end_conversation"] as const;
export type DeskAction = (typeof DESK_ACTIONS)[number];

/**
 * Calling someone other than the user is an outward action, so like memories it needs the
 * user's own words: they asked for a call, or said yes right after Desku offered/read it back.
 */
export function allowsCall(userWords: string, previousAssistant: string | null): boolean {
  const said = userWords.toLowerCase();
  if (/\b(call|ring|phone|dial)\b/.test(said)) return true;
  const offered = /\b(call|ring|phone|dial)\b[^?]*\?/.test((previousAssistant ?? "").toLowerCase());
  return offered && /^\s*(yes|yeah|yep|yup|sure|ok|okay|do it|go ahead|please do|correct|that's right|right)\b/.test(said);
}

const FOCUS_CHECKIN = "Focus check-in: ";

export function localTime(ms: number, timeZone: string) {
  return new Intl.DateTimeFormat("en-US", {
    timeZone,
    weekday: "long",
    month: "long",
    day: "numeric",
    hour: "numeric",
    minute: "2-digit",
    timeZoneName: "short",
  }).format(ms);
}

/** Desku's function tools, run on this server when the Agents session asks for them. */
export function makeTools(ctx: ToolContext): ToolRunner {
  const { store } = ctx;
  const ok = (output: string | InputContentParam[]): ToolResult => ({ ok: true, output });
  const err = (error: string): ToolResult => ({ ok: false, error });
  const str = (v: unknown) => (typeof v === "string" ? v.trim() : "");

  return async (name, args) => {
    const now = ctx.now();
    switch (name) {
      case "get_desk_status": {
        const focus = store.focus();
        const pending = store.pendingCheckins();
        return ok(
          JSON.stringify({
            local_time: localTime(now, ctx.timeZone),
            focus: focus && { task: focus.task, minutes_ago: Math.floor((now - focus.startedAt) / 60_000) },
            recently_finished: store.recentFocus(3).filter((f) => f.doneAt).map((f) => ({ task: f.task, outcome: f.outcome })),
            memories: store.memories().map(({ id, text }) => ({ id, text })),
            due_checkins: pending.filter((c) => c.dueAt <= now).map((c) => c.about),
            upcoming_checkins: pending
              .filter((c) => c.dueAt > now)
              .map((c) => ({ about: c.about, in_minutes: Math.ceil((c.dueAt - now) / 60_000) })),
            camera: ctx.cameraOn() ? "on" : "off",
            user_at_desk: (() => {
              const p = ctx.presence?.();
              if (!p || p.atDesk === null) return "unknown";
              return p.atDesk ? `yes, for ${p.minutes} min` : `no, away for ${p.minutes} min`;
            })(),
          }),
        );
      }
      case "set_focus": {
        const task = str(args.task);
        if (!task) return err("task is required");
        const minutes = typeof args.checkin_minutes === "number" ? args.checkin_minutes : null;
        store.setFocus(task);
        store.cancelCheckins((c) => c.about.startsWith(FOCUS_CHECKIN));
        if (minutes && minutes > 0) {
          store.addCheckin(now + minutes * 60_000, FOCUS_CHECKIN + task);
          return ok(`Focus set. Check-in scheduled in ${minutes} minutes.`);
        }
        return ok("Focus set. No check-in scheduled.");
      }
      case "save_memory": {
        const text = str(args.text);
        if (!text) return err("text is required");
        // Enforced here, not just in the prompt: only the user's own request can save a memory.
        if (!allowsSave(ctx.userWords, ctx.previousAssistant)) {
          return err("Not saved: the user didn't ask you to remember this. You can offer to remember it instead.");
        }
        return ok(`Saved as ${store.addMemory(text).id}.`);
      }
      case "delete_memory": {
        const id = str(args.id);
        return store.deleteMemory(id) ? ok(`Deleted ${id}.`) : err(`No memory with id ${id}.`);
      }
      case "look_through_camera": {
        if (!ctx.cameraOn()) return err("The camera is switched off on the desk phone. Tell the user they can turn it on.");
        try {
          const jpeg = await ctx.takePhoto();
          return ok([
            { type: "input_text", text: `Photo from the desk camera, taken ${localTime(ctx.now(), ctx.timeZone)}.` },
            { type: "input_image", image_url: `data:image/jpeg;base64,${jpeg}` },
          ]);
        } catch (e) {
          return err(`No photo: ${(e as Error).message}`);
        }
      }
      case "call_phone": {
        if (!ctx.placeCall) return err("Phone calls aren't set up on the Desku server (AGENTPHONE_API_KEY and OWNER_PHONE in server/.env).");
        const raw = str(args.to);
        const to = raw ? raw.replace(/[^\d+]/g, "") : null;
        if (to && !/^\+1\d{10}$/.test(to)) return err(`"${raw}" isn't a US or Canada number in +1XXXXXXXXXX form. Ask the user to repeat it.`);
        if (to && !allowsCall(ctx.userWords, ctx.previousAssistant)) {
          return err("Not called: the user's own words didn't ask for this call. Read the number back and ask if you should call it.");
        }
        const problem = ctx.placeCall({ to, reason: str(args.reason) || "a quick chat", callerName: str(args.caller_name) || null });
        if (problem) return err(problem);
        return ok(to ? `Calling ${to} now. You'll hear back how it went when the call ends.` : "Calling the user's phone now. It should ring in a few seconds.");
      }
      case "show_page": {
        if (!ctx.pages) return err("Pages aren't available on this server.");
        try {
          const page = ctx.pages.save(str(args.title) || "Desku", typeof args.html === "string" ? args.html : "", str(args.page_id) || null);
          ctx.onPage?.(page);
          return ok(`Shown on the desk screen as page ${page.id}. To update it later, pass page_id "${page.id}".`);
        } catch (e) {
          return err((e as Error).message);
        }
      }
      case "control_desk": {
        const action = str(args.action) as DeskAction;
        if (!DESK_ACTIONS.includes(action)) return err(`Unknown action. Use one of: ${DESK_ACTIONS.join(", ")}.`);
        if (!ctx.control) return err("The desk phone isn't connected.");
        return ok(ctx.control(action));
      }
      default:
        if (ctx.apps?.has(name)) return ctx.apps.run(name, args, ctx.onLink);
        return err(`Unknown function ${name}`);
    }
  };
}
