// Live check of apps: real Composio session + real Desku brain, asking about the calendar.
// Prints any Connect Link the user has to open. Usage: npx tsx --env-file=.env scripts/smoke-apps.ts ["question"]
import { readFileSync } from "node:fs";
import { Composio } from "@composio/core";
import OpenAI from "openai";
import { Apps } from "../src/apps/Apps.ts";
import { AgentBrain } from "../src/brain/AgentBrain.ts";
import { loadConfig } from "../src/config.ts";
import { makeTools } from "../src/engine/tools.ts";
import { Store } from "../src/store/Store.ts";

const config = loadConfig();
const store = new Store(config.dataDir);
const apps = new Apps(new Composio({ apiKey: config.composioApiKey }), store, config.composioUserId);
const definition = JSON.parse(readFileSync(config.agentDefinition, "utf8"));
definition.tools.push(...(await apps.init()));
const brain = new AgentBrain(new OpenAI({ apiKey: config.openaiApiKey, project: config.openaiProject }), definition, store);
await brain.init(config.agentId);

const heard = process.argv[2] ?? "What's on my calendar for the rest of today?";
console.log(`USER: ${heard}`);
const answer = await brain.ask(`[Heard] ${heard}`, makeTools({
  store, timeZone: config.timeZone, now: Date.now, userWords: heard, previousAssistant: null,
  cameraOn: () => false, takePhoto: async () => { throw new Error("no camera in smoke test"); },
  apps, onLink: (url) => console.log(`\n>>> CONNECT LINK (open to authorize): ${url}\n`),
}));
console.log(`DESKU: ${answer}`);
