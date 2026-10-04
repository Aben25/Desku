import assert from "node:assert/strict";
import { once } from "node:events";
import { test } from "node:test";
import { WebSocketServer, type WebSocket } from "ws";
import { LiveClient } from "../src/live/LiveClient.ts";

async function mockLive() {
  const wss = new WebSocketServer({ port: 0 });
  await once(wss, "listening");
  const port = (wss.address() as { port: number }).port;
  const received: any[] = [];
  let socket!: WebSocket;
  let auth = "";
  wss.on("connection", (ws, req) => {
    socket = ws;
    auth = req.headers.authorization ?? "";
    ws.on("message", (d) => received.push(JSON.parse(d.toString())));
  });
  return {
    url: `ws://localhost:${port}`,
    received,
    auth: () => auth,
    send: (e: object) => socket.send(JSON.stringify(e)),
    waitFor: async (type: string) => {
      for (let i = 0; i < 100; i++) {
        const hit = received.find((e) => e.type === type);
        if (hit) return hit;
        await new Promise((r) => setTimeout(r, 10));
      }
      throw new Error(`never received ${type}`);
    },
    close: () => wss.close(),
  };
}

test("starts a client-delegation session, queues commands until started, parses events", async () => {
  const server = await mockLive();
  const live = new LiveClient({ url: server.url, apiKey: "sk-test", session: { model: "gpt-live-1", delegation: { type: "client" } } });
  const events: unknown[] = [];
  live.on("transcript", (...a) => events.push(["transcript", ...a]));
  live.on("delegation", (id) => events.push(["delegation", id]));
  live.on("audio", (pcm) => events.push(["audio", pcm.length]));
  live.connect();

  const start = await server.waitFor("session.start");
  assert.equal(server.auth(), "Bearer sk-test");
  assert.deepEqual(start.session, { model: "gpt-live-1", delegation: { type: "client" } });

  live.commentary("queued", null);
  live.appendAudio(Buffer.alloc(4)); // dropped: not started yet
  await new Promise((r) => setTimeout(r, 30));
  assert.equal(server.received.length, 1);

  server.send({ type: "session.started", event_id: "e1", session: { id: "sess_1" } });
  const commentary = await server.waitFor("session.commentary.append");
  assert.equal(commentary.content, "queued");
  assert.equal(commentary.delegation_id, null);

  live.appendAudio(Buffer.from([1, 0, 2, 0]));
  const audio = await server.waitFor("session.input_audio.append");
  assert.equal(audio.audio, Buffer.from([1, 0, 2, 0]).toString("base64"));

  server.send({ type: "session.input_transcript.delta", delta: "hi", start_ms: 5, end_ms: 9 });
  server.send({ type: "session.output_audio.delta", delta: Buffer.alloc(6).toString("base64"), start_ms: 0, end_ms: 1 });
  server.send({ type: "session.delegation.created", offset_ms: 1, delegation: { id: "del_1", type: "delegation", target: "client" } });
  await new Promise((r) => setTimeout(r, 30));
  assert.deepEqual(events, [["transcript", "user", "hi", 5], ["audio", 6], ["delegation", "del_1"]]);

  live.commentary("x".repeat(5000), "del_1");
  await new Promise((r) => setTimeout(r, 30));
  const long = server.received.at(-1);
  assert.ok(long.content.length <= 1800, "clipped to the 500-token limit");

  const closed = once(live, "closed");
  live.close();
  await server.waitFor("session.close");
  server.send({ type: "session.closed", reason: "close_requested", usage: { seconds: 3 } });
  assert.deepEqual(await closed, ["close_requested"]);
  assert.equal(live.isOpen, false);
  server.close();
});
