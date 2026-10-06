import assert from "node:assert/strict";
import { EventEmitter } from "node:events";
import { mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { test } from "node:test";
import { Desk, type Live } from "../src/engine/Desk.ts";
import { AgentPhone, type CallRequest } from "../src/phone/AgentPhone.ts";
import { Store } from "../src/store/Store.ts";

class FakeLive extends EventEmitter {
  sent: unknown[][] = [];
  isOpen = true;
  connect() {}
  appendAudio() {}
  commentary(t: string, id: string | null) { this.sent.push(["commentary", t, id]); }
  thinking() {}
  mute() {}
  unmute() {}
  close() { this.isOpen = false; this.sent.push(["close"]); this.emit("closed", "close_requested"); }
}
class FakeDevice extends EventEmitter {
  readyState = 1;
  json: any[] = [];
  send(d: any, o?: { binary?: boolean }) { if (!o?.binary) this.json.push(JSON.parse(d)); }
  close() { this.readyState = 3; this.emit("close"); }
}

const tick = (ms = 20) => new Promise((r) => setTimeout(r, ms));

function setup(startIso = "2026-10-05T18:00:00Z") { // 2 PM in New York
  let clock = Date.parse(startIso);
  const store = new Store(mkdtempSync(join(tmpdir(), "desku-away-")), () => clock);
  const asked: string[] = [];
  const calls: CallRequest[] = [];
  const lives: FakeLive[] = [];
  const brainTools: Array<(n: string, a: Record<string, unknown>) => Promise<unknown>> = [];
  const desk = new Desk(store, {
    ask: async (t, tools) => { asked.push(t); brainTools.push(tools); return "How's the grant intro going?"; },
  }, () => { const l = new FakeLive(); lives.push(l); return l as unknown as Live; },
  { idleCloseSeconds: 600, timeZone: "America/New_York" }, () => clock, () => {}, 0);
  desk.enableAwayCalls({
    caller: { call: async (req) => { calls.push(req); return { id: "call_1", status: "completed", transcript: [
      { role: "agent", content: req.initialGreeting },
      { role: "user", content: "Going fine. Remember that I owe Sam a reply." },
    ] }; } },
    toNumber: "+15555550123",
    noReplySeconds: 60,
    quietHours: [22, 8],
  });
  store.addCheckin(clock + 25 * 60_000, "Focus check-in: grant intro");
  return { store, desk, asked, calls, lives, brainTools, advance: (ms: number) => (clock += ms) };
}

test("desk phone offline: a due check-in becomes a phone call, and the brain hears how it went", async () => {
  const { desk, calls, asked, store, advance } = setup();
  advance(25 * 60_000);
  await desk.tick();
  await tick();
  assert.equal(calls.length, 1);
  assert.equal(calls[0].toNumber, "+15555550123");
  assert.match(calls[0].initialGreeting, /it's Desku, your AI desk buddy.*grant intro/);
  assert.match(calls[0].systemPrompt, /desk phone is offline/);
  assert.match(asked[0], /^\[Desk event\] The user was away, so you phoned them[\s\S]*User: Going fine/);
  assert.equal(store.pendingCheckins().length, 0);
});

test("the user's words on the call count for the memory rule", async () => {
  const { desk, brainTools, store, advance } = setup();
  advance(25 * 60_000);
  await desk.tick();
  await tick();
  const r: any = await brainTools[0]("save_memory", { text: "User owes Sam a reply." });
  assert.equal(r.ok, true);
  assert.equal(store.memories().length, 1);
});

test("at the desk but no reply: call after the wait; a reply cancels it", async () => {
  const a = setup();
  const device = new FakeDevice();
  a.desk.attach(device as any);
  a.advance(25 * 60_000);
  await a.desk.tick();
  assert.deepEqual(a.lives[0].sent.at(-1), ["commentary", "How's the grant intro going?", null]);
  a.advance(30_000);
  await a.desk.tick();
  assert.equal(a.calls.length, 0, "still waiting");
  a.advance(31_000);
  await a.desk.tick();
  await tick();
  assert.equal(a.calls.length, 1);
  assert.match(a.calls[0].systemPrompt, /nobody answered/);
  assert.equal(device.json.filter((m) => m.type === "calling").length, 1);

  const b = setup();
  const phone = new FakeDevice();
  b.desk.attach(phone as any);
  b.advance(25 * 60_000);
  await b.desk.tick();
  b.lives[0].emit("transcript", "user", "Pretty good!", 1000);
  b.advance(120_000);
  await b.desk.tick();
  assert.equal(b.calls.length, 0);
});

test("quiet hours: no call", async () => {
  const { desk, calls, advance } = setup("2026-10-06T02:00:00Z"); // 10 PM in New York
  advance(25 * 60_000);
  await desk.tick();
  await tick();
  assert.equal(calls.length, 0);
});

test("AgentPhone client: finds the sender, places the call, polls, returns transcript", async () => {
  const requests: string[] = [];
  let polls = 0;
  const fakeFetch = (async (url: string, init: RequestInit) => {
    const path = url.replace("https://api.agentphone.ai", "");
    requests.push(`${init.method} ${path}${init.body ? ` ${init.body}` : ""}`);
    assert.equal((init.headers as Record<string, string>).Authorization, "Bearer sk_live_test");
    const json = (b: unknown) => new Response(JSON.stringify(b), { status: 200 });
    if (path.startsWith("/v1/numbers")) return json({ data: [{ id: "num_9", phoneNumber: "+16282841240", agentId: "agt_1" }] });
    if (path === "/v1/calls") return json({ id: "call_7", status: "in-progress" });
    if (path === "/v1/calls/call_7") return json({ status: ++polls < 2 ? "in-progress" : "completed" });
    if (path === "/v1/calls/call_7/transcript") return json({ transcript: [{ role: "user", content: "hi" }] });
    return new Response("nope", { status: 404 });
  }) as typeof fetch;
  const phone = new AgentPhone("sk_live_test", "+16282841240", () => {}, fakeFetch, 1);
  const result = await phone.call({ toNumber: "+15555550123", systemPrompt: "p", initialGreeting: "g" });
  assert.deepEqual(result, { id: "call_7", status: "completed", transcript: [{ role: "user", content: "hi" }] });
  assert.equal(requests[1], 'POST /v1/calls {"agentId":"agt_1","fromNumberId":"num_9","toNumber":"+15555550123","systemPrompt":"p","initialGreeting":"g"}');
});

test("AgentPhone client: payment errors are explained", async () => {
  const fakeFetch = (async (url: string) =>
    url.includes("/v1/numbers")
      ? new Response(JSON.stringify({ data: [{ id: "n", phoneNumber: "+16282841240", agentId: "a" }] }))
      : new Response('{"detail":"payment required"}', { status: 402 })) as typeof fetch;
  const phone = new AgentPhone("k", "+16282841240", () => {}, fakeFetch, 1);
  await assert.rejects(phone.call({ toNumber: "+1", systemPrompt: "", initialGreeting: "" }), /402 \(outbound calls need a payment method/);
});

test("call_phone to the user: calls right away when asked, even in quiet hours; explains when not set up", async () => {
  const { desk, calls, brainTools, store } = setup("2026-10-06T03:00:00Z"); // 11 PM in New York
  const device = new FakeDevice();
  desk.attach(device as any);
  store.cancelCheckins();
  // A screen tap gives the brain a tool runner, like a voice turn would.
  (desk as any).onTapped("Call me please");
  await tick();
  const r: any = await brainTools[0]("call_phone", { to: null, reason: "chat about the hackathon", caller_name: null });
  assert.equal(r.ok, true);
  await tick();
  assert.equal(calls.length, 1);
  assert.match(calls[0].initialGreeting, /calling like you asked/);
  assert.match(calls[0].systemPrompt, /the user asked Desku to call them\. Topic: chat about the hackathon/);
});

test("call_phone to a friend: only when the user asked, validated, and the outcome is reported", async () => {
  const { desk, calls, brainTools, asked, store, lives } = setup();
  store.cancelCheckins();
  const device = new FakeDevice();
  desk.attach(device as any);
  (desk as any).onTapped("Call my friend at +1 555 010 0199 and tell her I'm running late");
  await tick();
  const tools = brainTools[0];
  const bad: any = await tools("call_phone", { to: "911", reason: "x", caller_name: null });
  assert.equal(bad.ok, false);
  assert.match(bad.error, /isn't a US or Canada number/);
  const r: any = await tools("call_phone", { to: "+1 (555) 010-0199", reason: "running late", caller_name: "Sam" });
  assert.equal(r.ok, true);
  await tick();
  assert.equal(calls.length, 1);
  assert.equal(calls[0].toNumber, "+15550100199");
  assert.match(calls[0].initialGreeting, /AI assistant calling on behalf of Sam/);
  assert.match(calls[0].systemPrompt, /Purpose, in their words: running late/);
  assert.match(asked.at(-1)!, /^\[Desk event\] You phoned \+15550100199 for the user/);
  assert.ok(lives.at(-1)!.sent.some((m) => m[0] === "commentary" && m[2] === null), "outcome spoken at the desk");
});

test("call_phone to someone else is refused when the user never asked for a call", async () => {
  const { desk, calls, brainTools, store } = setup();
  store.cancelCheckins();
  desk.attach(new FakeDevice() as any);
  (desk as any).onTapped("What's on my list today?");
  await tick();
  const r: any = await brainTools[0]("call_phone", { to: "+15550100199", reason: "hi", caller_name: null });
  assert.equal(r.ok, false);
  assert.match(r.error, /didn't ask for this call/);
  assert.equal(calls.length, 0);
});

test("camera says nobody's at the desk: a due check-in calls right away", async () => {
  const { desk, calls, advance } = setup();
  const device = new FakeDevice();
  desk.attach(device as any);
  device.emit("message", Buffer.from(JSON.stringify({ type: "presence", present: true })), false);
  device.emit("message", Buffer.from(JSON.stringify({ type: "presence", present: false })), false);
  advance(25 * 60_000);
  await desk.tick();
  await tick();
  assert.equal(calls.length, 1);
  assert.match(calls[0].systemPrompt, /desk camera hasn't seen them/);
});

test("call consent can span the exchange: 'call my friend' … number … 'yes' … message", async () => {
  const { desk, calls, brainTools, store, lives } = setup();
  store.cancelCheckins();
  desk.attach(new FakeDevice() as any);
  (desk as any).openLive();
  const live = lives.at(-1)!;
  live.emit("started", "s");
  for (const [i, words] of ["Can you call my friend", "two four zero, five five five, zero one nine nine", "Yeah, that's correct", "Tell them I'm running late for the soccer game"].entries()) {
    live.emit("transcript", "user", words, i * 1000);
    live.emit("transcript", "assistant", "Okay?", i * 1000 + 500);
  }
  live.emit("transcript", "user", " ", 9000);
  live.emit("delegation", "d1");
  await tick();
  const r: any = await brainTools.at(-1)!("call_phone", { to: "+12405550199", reason: "running late for the soccer game", caller_name: null });
  assert.equal(r.ok, true, r.error);
  await tick();
  assert.equal(calls.length, 1);
});

test("no credit on the phone line: Desku says so plainly", async () => {
  const { desk, store, lives } = setup();
  store.cancelCheckins();
  desk.enableAwayCalls({
    caller: { call: async () => { throw new Error("AgentPhone POST /v1/calls → 402 (outbound calls need a payment method): {...}"); } },
    toNumber: "+15555550123", noReplySeconds: 60, quietHours: null,
  });
  desk.attach(new FakeDevice() as any);
  await (desk as any).callForUser("+15550100199", "hi", null);
  assert.match(String(lives.at(-1)!.sent.at(-1)?.[1]), /no credit yet\. Add funds on the AgentPhone billing page/);
});

test("right after startup, an offline phone isn't treated as 'away' yet", async () => {
  const { desk, calls, store, advance } = setup();
  store.cancelCheckins();
  store.addCheckin(Date.now() - 1000, "Focus check-in: stale");
  advance(0);
  await desk.tick();
  await tick();
  assert.equal(calls.length, 0, "grace period after boot");
  advance(3 * 60_000);
  await desk.tick();
  await tick();
  assert.equal(calls.length, 1);
});
