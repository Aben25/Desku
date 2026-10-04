// Live check of the brain alone: real Agents API, real tools, a fake camera.
// Usage: npx tsx --env-file=.env scripts/smoke-brain.ts
import { readFileSync, mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import OpenAI from "openai";
import { loadConfig } from "../src/config.ts";
import { AgentBrain } from "../src/brain/AgentBrain.ts";
import { makeTools } from "../src/engine/tools.ts";
import { Store } from "../src/store/Store.ts";

const config = loadConfig();
const store = new Store(mkdtempSync(join(tmpdir(), "desku-smoke-")));
const client = new OpenAI({ apiKey: config.openaiApiKey, project: config.openaiProject });
const brain = new AgentBrain(client, JSON.parse(readFileSync(config.agentDefinition, "utf8")), store);
await brain.init(config.agentId);

const photo = readFileSync(new URL("../../demo/handwritten-todo.jpg", import.meta.url)).toString("base64");
const say = async (heard: string) => {
  console.log(`\nUSER: ${heard}`);
  const tools = makeTools({
    store, timeZone: config.timeZone, now: Date.now, userWords: heard, previousAssistant: null,
    cameraOn: () => true, takePhoto: async () => photo,
  });
  const answer = await brain.ask(`[Heard] ${heard}`, tools, (p, tools) => console.log(`  (progress before ${tools}) ${p}`));
  console.log(`DESKU: ${answer}`);
};

await say("Can you read my to-do list? I'm holding it up.");
await say("Okay, I'll do the first one for 25 minutes. Check in on me after.");
await say("The grant is due Friday, just so you know."); // not a save request: must not be saved
console.log("\nstore:", JSON.stringify({ memories: store.memories(), focus: store.focus(), checkins: store.pendingCheckins() }, null, 1));
