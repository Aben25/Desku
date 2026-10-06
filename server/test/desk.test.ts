import assert from "node:assert/strict";
import { EventEmitter } from "node:events";
import { mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { test } from "node:test";
import type { Brain, Progress } from "../src/brain/AgentBrain.ts";
import { Desk, type Live } from "../src/engine/Desk.ts";
import type { ToolRunner } from "../src/engine/tools.ts";
import { Store } from "../src/store/Store.ts";

class FakeLive extends EventEmitter {
  sent: Array<[string, ...unknown[]]> = [];
  isOpen = true;
  constructor(public instructions: string) { super(); }
  connect() { this.sent.push(["connect"]); }
  appendAudio(pcm: Buffer) { this.sent.push(["audio", pcm.length]); }
  commentary(text: string, id: string | null) { this.sent.push(["commentary", text, id]); }
  thinking(text: string, id: string | null) { this.sent.push(["thinking", text, id]); }
  mute() { this.sent.push(["mute"]); }
  unmute() { this.sent.push(["unmute"]); }
  close() { this.sent.push(["close"]); this.isOpen = false; this.emit("closed", "close_requested"); }
  started() { this.emit("started", "live_1"); }
}

/** Scripted brain: runs the given tool calls, then answers. */
class FakeBrain implements Brain {
  asked: string[] = [];
  constructor(private script: (text: string, tools: ToolRunner, progress?: Progress) => Promise<string>) {}
  ask(text: string, tools: ToolRunner, progress?: Progress) { this.asked.push(text); return this.script(text, tools, progress); }
}

class FakeDevice extends EventEmitter {
  readyState = 1;
  json: any[] = [];
  binary: number[] = [];
  send(data: any, opts?: { binary?: boolean }) { if (opts?.binary) this.binary.push(data.length); else this.json.push(JSON.parse(data)); }
  close() { this.readyState = 3; this.emit("close"); }
  say(msg: object) { this.emit("message", Buffer.from(JSON.stringify(msg)), false); }
  mic(bytes: number) { this.emit("message", Buffer.alloc(bytes), true); }
  last(type: string) { return this.json.filter((m) => m.type === type).at(-1); }
}

const tick = (ms = 20) => new Promise((r) => setTimeout(r, ms));

function setup(script: ConstructorParameters<typeof FakeBrain>[0]) {
  let clock = Date.parse("2026-10-05T14:00:00Z");
  const store = new Store(mkdtempSync(join(tmpdir(), "desku-test-")), () => clock);
  const brain = new FakeBrain(script);
  const lives: FakeLive[] = [];
  const desk = new Desk(store, brain, (i) => { const l = new FakeLive(i); lives.push(l); return l as unknown as Live; },
    { idleCloseSeconds: 60, timeZone: "America/New_York" }, () => clock, () => {}, 0);
  const device = new FakeDevice();
  desk.attach(device as any);
  return { store, brain, lives, desk, device, advance: (ms: number) => (clock += ms) };
}

test("voice turn: mic audio flows, delegation goes to the brain, answer is spoken", async () => {
  const { lives, device, brain, store } = setup(async (_t, tools) => {
    const r = await tools("set_focus", { task: "grant intro", checkin_minutes: 25 });
    assert.ok(r.ok);
    return "You're set on the grant intro. I'll check in in 25 minutes.";
  });
  device.say({ type: "start" });
  const live = lives[0];
  assert.match(live.instructions, /Delegate to the backend when/);
  device.mic(4800); // before started: dropped
  live.started();
  device.mic(4800);
  assert.deepEqual(live.sent, [["connect"], ["audio", 4800]]);
  assert.equal(device.last("state").live, "open");

  live.emit("transcript", "user", "I'll work on the grant intro, ", 0);
  live.emit("transcript", "user", "check in after 25.", 900);
  live.emit("delegation", "del_1");
  await tick();
  assert.match(brain.asked[0], /^\[Heard\] I'll work on the grant intro, check in after 25\./);
  assert.deepEqual(live.sent.at(-1), ["commentary", "You're set on the grant intro. I'll check in in 25 minutes.", "del_1"]);
  assert.equal(store.focus()?.task, "grant intro");
  assert.equal(store.pendingCheckins().length, 1);
  assert.equal(device.last("desk").focus.task, "grant intro");
  assert.deepEqual(device.last("transcript"), { type: "transcript", role: "user", delta: "check in after 25." });
});

test("memory is refused unless the user asked", async () => {
  const results: unknown[] = [];
  const { lives, device, store } = setup(async (_t, tools) => {
    results.push(await tools("save_memory", { text: "Grant due Friday." }));
    return "ok";
  });
  device.say({ type: "start" });
  lives[0].started();
  lives[0].emit("transcript", "user", "The grant is due Friday.", 0);
  lives[0].emit("delegation", "d1");
  await tick();
  lives[0].emit("transcript", "user", "Remember that the grant is due Friday.", 5000);
  lives[0].emit("delegation", "d2");
  await tick();
  assert.equal((results[0] as any).ok, false);
  assert.equal((results[1] as any).ok, true);
  assert.deepEqual(store.memories().map((m) => m.text), ["Grant due Friday."]);
});

test("camera: progress is spoken, phone takes the photo, image goes back to the brain", async () => {
  let toolResult: any;
  const { lives, device } = setup(async (_t, tools, progress) => {
    progress?.("Hold it up, counting down.", ["look_through_camera"]);
    toolResult = await tools("look_through_camera", {});
    return "It's a to-do list with five items.";
  });
  device.say({ type: "start" });
  lives[0].started();
  lives[0].emit("transcript", "user", "Read this", 0);
  lives[0].emit("delegation", "d1");
  await tick();
  assert.deepEqual(lives[0].sent.at(-1), ["commentary", "Hold it up, counting down.", "d1"]);
  const capture = device.last("capture");
  assert.equal(capture.countdownSeconds, 0);
  device.say({ type: "photo", requestId: capture.requestId, image: "SlBFRw==" });
  await tick();
  assert.equal(toolResult.ok, true);
  assert.equal(toolResult.output[1].image_url, "data:image/jpeg;base64,SlBFRw==");
  assert.deepEqual(lives[0].sent.at(-1), ["commentary", "It's a to-do list with five items.", "d1"]);
});

test("camera switched off: tool fails without asking the phone", async () => {
  let toolResult: any;
  const { lives, device } = setup(async (_t, tools) => { toolResult = await tools("look_through_camera", {}); return "Camera's off."; });
  device.say({ type: "camera", enabled: false });
  device.say({ type: "start" });
  lives[0].started();
  lives[0].emit("delegation", "d1");
  await tick();
  assert.equal(toolResult.ok, false);
  assert.equal(device.last("capture"), undefined);
});

test("proactive check-in: opens the voice session and Desku speaks first", async () => {
  const { lives, device, desk, store, brain, advance } = setup(async (text) =>
    text.startsWith("[Desk event]") ? "Hey, 25 minutes are up. How did the grant intro go?" : "ok");
  const due = store.addCheckin(Date.parse("2026-10-05T14:25:00Z"), "Focus check-in: grant intro");
  await desk.tick();
  assert.equal(lives.length, 0, "not due yet");
  advance(25 * 60_000);
  await desk.tick();
  assert.equal(lives.length, 1);
  assert.match(brain.asked[0], /^\[Desk event\] Check-in due: Focus check-in: grant intro/);
  assert.deepEqual(lives[0].sent.at(-1), ["commentary", "Hey, 25 minutes are up. How did the grant intro go?", null]);
  assert.equal(device.last("checkin").about, "Focus check-in: grant intro");
  assert.equal(store.pendingCheckins().find((c) => c.id === due.id), undefined, "fired once");
});

test("check-ins wait while the phone is offline", async () => {
  const { lives, device, desk, store, advance } = setup(async () => "How's it going?");
  store.addCheckin(Date.parse("2026-10-05T14:01:00Z"), "Focus check-in: x");
  device.close();
  advance(5 * 60_000);
  await desk.tick();
  assert.equal(lives.length, 0);
  assert.equal(store.pendingCheckins().length, 1);
  const phone = new FakeDevice();
  desk.attach(phone as any);
  await tick();
  assert.equal(lives.length, 1);
});

test("mute stops audio and tells GPT-Live; idle session closes", async () => {
  const { lives, device, desk, advance } = setup(async () => "ok");
  device.say({ type: "start" });
  lives[0].started();
  device.say({ type: "mute" });
  device.mic(4800);
  assert.deepEqual(lives[0].sent.slice(1), [["mute"]]);
  assert.equal(device.last("state").muted, true);
  advance(61_000);
  await desk.tick();
  assert.deepEqual(lives[0].sent.at(-1), ["close"]);
  assert.equal(device.last("state").live, "idle");
});

test("brain failure: says sorry and reports the error", async () => {
  const { lives, device } = setup(async () => { throw new Error("server_overloaded"); });
  device.say({ type: "start" });
  lives[0].started();
  lives[0].emit("delegation", "d1");
  await tick();
  assert.match(String(lives[0].sent.at(-1)?.[1]), /^Sorry/);
  assert.match(device.last("error").message, /server_overloaded/);
});

test("a tap on the kiosk screen is handled like speech and spoken back", async () => {
  const { lives, device, brain, store } = setup(async (_t, tools) => {
    await tools("set_focus", { task: "invoices", checkin_minutes: 10 });
    return "Okay, ten more minutes on invoices.";
  });
  device.say({ type: "say", text: "10 more minutes, please." });
  lives[0].started();
  await tick();
  assert.equal(brain.asked[0], "[Tapped on the desk screen] 10 more minutes, please.");
  assert.deepEqual(lives[0].sent.at(-1), ["commentary", "Okay, ten more minutes on invoices.", null]);
  assert.equal(store.focus()?.task, "invoices");
});

test("a screen tap isn't repeated in the next spoken request", async () => {
  const { lives, device, brain } = setup(async () => "ok");
  device.say({ type: "say", text: "Say hello." });
  lives[0].started();
  await tick();
  lives[0].emit("transcript", "user", "Any email I should look at?", 900);
  lives[0].emit("delegation", "d1");
  await tick();
  assert.match(brain.asked[1], /^\[Heard\] Any email I should look at\?/);
});

test("control_desk: camera off, mute and memories reach the phone; end closes after the goodbye", async () => {
  const results: any[] = [];
  const { lives, device, desk, advance } = setup(async (_t, tools) => {
    results.push(await tools("control_desk", { action: "camera_off" }));
    results.push(await tools("control_desk", { action: "mute_mic" }));
    results.push(await tools("control_desk", { action: "show_memories" }));
    results.push(await tools("control_desk", { action: "fly_away" }));
    results.push(await tools("control_desk", { action: "end_conversation" }));
    return "Okay, bye!";
  });
  device.say({ type: "start" });
  lives[0].started();
  lives[0].emit("delegation", "d1");
  await tick();
  assert.deepEqual(device.json.filter((m) => m.type === "control").map((m) => m.action), ["camera_off", "mute_mic", "show_memories", "end_conversation"]);
  assert.equal(results[3].ok, false);
  const state = device.last("state");
  assert.equal(state.camera, false);
  assert.equal(state.muted, true);
  assert.ok(lives[0].sent.some((m) => m[0] === "mute"));
  advance(4_000);
  await desk.tick();
  assert.deepEqual(lives[0].sent.at(-1), ["close"]);
});

test("show_page saves the page and tells the phone where it is", async () => {
  const { Pages } = await import("../src/pages/Pages.ts");
  let result: any;
  const { lives, device, desk } = setup(async (_t, tools) => {
    result = await tools("show_page", { title: "Tickets", html: "<!doctype html><html><body><h1>3 open</h1></body></html>", page_id: null });
    return "Your tickets are on the screen.";
  });
  desk.enablePages(new Pages(mkdtempSync(join(tmpdir(), "desku-pages-"))));
  device.say({ type: "start" });
  lives[0].started();
  lives[0].emit("delegation", "d1");
  await tick();
  assert.equal(result.ok, true);
  const page = device.last("page");
  assert.equal(page.title, "Tickets");
  assert.match(page.url, new RegExp(`^/p/${page.id}\\?v=\\d+$`), "versioned so the phone reloads updates");
  assert.match(result.output, new RegExp(`page_id "${page.id}"`));
});

test("presence: welcome back after a long absence follows up on the focus", async () => {
  const { lives, device, desk, brain, store, advance } = setup(async (t) => (t.startsWith("[Desk event] The camera") ? "Welcome back! How's the grant intro?" : "ok"));
  store.setFocus("grant intro");
  device.say({ type: "presence", present: true });
  await tick();
  device.say({ type: "presence", present: false });
  advance(12 * 60_000);
  device.say({ type: "presence", present: true });
  await tick();
  assert.match(brain.asked.at(-1)!, /came back to the desk after 12 minutes away\. Their focus was: grant intro/);
  assert.deepEqual(lives.at(-1)!.sent.at(-1), ["commentary", "Welcome back! How's the grant intro?", null]);
  // Not again right away.
  device.say({ type: "presence", present: false });
  advance(11 * 60_000);
  device.say({ type: "presence", present: true });
  await tick();
  assert.equal(brain.asked.filter((t) => t.includes("came back")).length, 1);
  void desk;
});

test("presence shows up in desk status; capture has no countdown", async () => {
  let status: any;
  const { lives, device } = setup(async (_t, tools) => {
    status = JSON.parse(((await tools("get_desk_status", {})) as any).output);
    void tools("look_through_camera", {});
    return "ok";
  });
  device.say({ type: "presence", present: true });
  device.say({ type: "start" });
  lives[0].started();
  lives[0].emit("delegation", "d1");
  await tick();
  assert.equal(status.user_at_desk, "yes, for 0 min");
  assert.equal(device.last("capture").countdownSeconds, 0);
});

test("always listening: opens on connect, reopens after a drop, Stop pauses until Talk", async () => {
  let clock = Date.parse("2026-10-05T14:00:00Z");
  const store = new Store(mkdtempSync(join(tmpdir(), "desku-listen-")), () => clock);
  const lives: FakeLive[] = [];
  const desk = new Desk(store, new FakeBrain(async () => "ok"), (i) => { const l = new FakeLive(i); lives.push(l); return l as unknown as Live; },
    { idleCloseSeconds: 60, timeZone: "UTC", alwaysListen: true }, () => clock, () => {}, 0);
  const device = new FakeDevice();
  desk.attach(device as any);
  assert.equal(lives.length, 1, "opened on connect");
  lives[0].started();
  clock += 10 * 60_000;
  await desk.tick();
  assert.ok(lives[0].isOpen, "no idle close while always listening");
  lives[0].emit("closed", "expired");
  clock += 11_000;
  await desk.tick();
  assert.equal(lives.length, 2, "reopened");
  device.say({ type: "stop" });
  clock += 60_000;
  await desk.tick();
  assert.equal(lives.length, 2, "paused after Stop");
  device.say({ type: "start" });
  assert.equal(lives.length, 3);
});

test("barge-in: user talking over Desku tells the phone to drop queued speech", async () => {
  const { lives, device, advance } = setup(async () => "ok");
  device.say({ type: "start" });
  lives[0].started();
  lives[0].emit("audio", Buffer.alloc(4800));
  lives[0].emit("transcript", "user", "wait, stop", 100);
  assert.equal(device.json.filter((m) => m.type === "interrupt").length, 1);
  advance(10_000);
  lives[0].emit("transcript", "user", " and another thing", 200);
  assert.equal(device.json.filter((m) => m.type === "interrupt").length, 1, "no interrupt when Desku isn't talking");
});

test("to-dos: add, check off, and the Today screen refreshes each time", async () => {
  const { Pages } = await import("../src/pages/Pages.ts");
  const results: any[] = [];
  const { lives, device, desk, store } = setup(async (_t, tools) => {
    results.push(await tools("add_todo", { text: "Finish the hackathon demo video" }));
    results.push(await tools("add_todo", { text: "Email Felipe" }));
    results.push(await tools("complete_todo", { todo: "demo video" }));
    results.push(await tools("complete_todo", { todo: "nonexistent thing" }));
    return "Added.";
  });
  const pages = new Pages(mkdtempSync(join(tmpdir(), "desku-today-")));
  desk.enablePages(pages);
  device.say({ type: "start" });
  lives[0].started();
  lives[0].emit("transcript", "user", "add finish the hackathon demo video to my to-dos", 0);
  lives[0].emit("delegation", "d1");
  await tick();
  assert.deepEqual(results.map((r) => r.ok), [true, true, true, false]);
  assert.deepEqual(store.todos().map((t) => [t.text, t.done]), [["Finish the hackathon demo video", true], ["Email Felipe", false]]);
  const shown = device.json.filter((m) => m.type === "page");
  assert.equal(shown.length, 3, "Today screen shown after every change");
  assert.equal(new Set(shown.map((m) => m.id)).size, 1, "same page, updated in place");
  const html = pages.html(shown[0].id)!;
  assert.match(html, /Email Felipe/);
  assert.match(html, /<s>Finish the hackathon demo video<\/s>/);
});

test("Today screen appears when the camera sees the user arrive", async () => {
  const { Pages } = await import("../src/pages/Pages.ts");
  const { device, desk, advance } = setup(async () => "ok");
  desk.enablePages(new Pages(mkdtempSync(join(tmpdir(), "desku-today2-"))));
  device.say({ type: "presence", present: true });
  assert.equal(device.json.filter((m) => m.type === "page").length, 1);
  device.say({ type: "presence", present: false });
  advance(30_000);
  device.say({ type: "presence", present: true });
  assert.equal(device.json.filter((m) => m.type === "page").length, 1, "not for a short glance away");
});
