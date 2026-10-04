// End-to-end check without a phone: acts as the desk device against a running server.
// Speaks lines (macOS `say` → 24 kHz PCM) into the live session, keeps sending silence like an
// open mic would, answers camera requests with demo/handwritten-todo.jpg, prints transcripts and
// saves Desku's audio to scratch WAV.
// Usage: npx tsx --env-file=.env scripts/fake-phone.ts "first line" "second line" ...
import { execFileSync } from "node:child_process";
import { mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import WebSocket from "ws";

const lines = process.argv.slice(2);
if (!lines.length) lines.push("Hey Desku, can you read my to-do list? I'm holding it up to the camera.");
const port = process.env.PORT ?? "8787";
const dir = mkdtempSync(join(tmpdir(), "desku-phone-"));
const photo = readFileSync(new URL("../../demo/handwritten-todo.jpg", import.meta.url)).toString("base64");

function tts(text: string, i: number): Buffer {
  const aiff = join(dir, `${i}.aiff`), wav = join(dir, `${i}.wav`);
  execFileSync("say", ["-o", aiff, text]);
  execFileSync("afconvert", ["-f", "WAVE", "-d", "LEI16@24000", "-c", "1", aiff, wav]);
  const buf = readFileSync(wav);
  const at = buf.indexOf("data");
  return buf.subarray(at + 8, at + 8 + buf.readUInt32LE(at + 4));
}

const out: Buffer[] = [];
const ws = new WebSocket(`ws://localhost:${port}/device?token=${process.env.DEVICE_TOKEN}`);
let line = "";
let lastRole = "";
ws.on("message", (data, isBinary) => {
  if (isBinary) return void out.push(data as Buffer);
  const msg = JSON.parse(data.toString());
  if (msg.type === "transcript") {
    if (msg.role !== lastRole) { process.stdout.write(`\n${msg.role === "user" ? "USER " : "DESKU"}: `); lastRole = msg.role; }
    process.stdout.write(msg.delta);
  } else if (msg.type === "capture") {
    console.log(`\n[phone] camera countdown ${msg.countdownSeconds}s → sending demo photo`);
    setTimeout(() => ws.send(JSON.stringify({ type: "photo", requestId: msg.requestId, image: photo })), msg.countdownSeconds * 1000);
  } else if (msg.type === "state") {
    const s = `${msg.live}${msg.thinking ? " thinking" : ""}`;
    if (s !== line) { console.log(`\n[state] ${s}`); line = s; }
  } else if (msg.type !== "desk") {
    console.log(`\n[${msg.type}] ${JSON.stringify(msg)}`);
  }
});

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));
const CHUNK = 2400 * 2; // 100 ms of 24 kHz PCM16
async function stream(pcm: Buffer, ms: number) {
  // Real time, like a mic. Silence when pcm runs out.
  const end = Date.now() + ms;
  for (let off = 0; Date.now() < end; off += CHUNK) {
    const chunk = off < pcm.length ? pcm.subarray(off, off + CHUNK) : Buffer.alloc(CHUNK);
    if (ws.readyState === WebSocket.OPEN) ws.send(chunk.length % 2 ? chunk.subarray(1) : chunk, { binary: true });
    await sleep(100);
  }
}

ws.on("open", async () => {
  ws.send(JSON.stringify({ type: "start" }));
  while (line !== "open") await sleep(100);
  await stream(Buffer.alloc(0), 1500);
  for (const [i, text] of lines.entries()) {
    const pcm = tts(text, i);
    await stream(pcm, pcm.length / 48 + Number(process.env.WAIT_MS ?? 25_000));
  }
  ws.send(JSON.stringify({ type: "stop" }));
  await sleep(2000);
  const pcm = Buffer.concat(out);
  const header = Buffer.alloc(44);
  header.write("RIFF", 0); header.writeUInt32LE(36 + pcm.length, 4); header.write("WAVEfmt ", 8);
  header.writeUInt32LE(16, 16); header.writeUInt16LE(1, 20); header.writeUInt16LE(1, 22);
  header.writeUInt32LE(24000, 24); header.writeUInt32LE(48000, 28); header.writeUInt16LE(2, 32);
  header.writeUInt16LE(16, 34); header.write("data", 36); header.writeUInt32LE(pcm.length, 40);
  const file = process.env.OUT_WAV ?? join(dir, "desku-reply.wav");
  writeFileSync(file, Buffer.concat([header, pcm]));
  console.log(`\n\nDesku audio: ${(pcm.length / 48000).toFixed(1)}s → ${file}`);
  ws.close();
});
ws.on("close", (code, reason) => { if (code !== 1000 && code !== 1005) console.log(`socket closed ${code} ${reason}`); });
