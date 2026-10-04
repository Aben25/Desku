// Live check of pages: real Desku brain + the user's real apps, asking for something to put on
// the screen. Uses a throwaway store and pages folder and no device socket, so it never touches
// a running desk phone. Writes the page, with the UI kit, to <tmp>/page.html to open in a browser.
// Usage: npx tsx --env-file=.env scripts/smoke-page.ts ["request"]
import { mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { Composio } from "@composio/core";
import OpenAI from "openai";
import { Apps } from "../src/apps/Apps.ts";
import { AgentBrain } from "../src/brain/AgentBrain.ts";
import { loadConfig } from "../src/config.ts";
import { makeTools } from "../src/engine/tools.ts";
import { Pages, withKit } from "../src/pages/Pages.ts";
import { Store } from "../src/store/Store.ts";

const config = loadConfig();
const dir = mkdtempSync(join(tmpdir(), "desku-page-"));
const store = new Store(dir);
const pages = new Pages(dir);
const definition = JSON.parse(readFileSync(config.agentDefinition, "utf8"));
let apps: Apps | null = null;
if (config.composioApiKey) {
  apps = new Apps(new Composio({ apiKey: config.composioApiKey }), store, config.composioUserId, () => {});
  definition.tools.push(...(await apps.init()));
}
const brain = new AgentBrain(
  new OpenAI({ apiKey: config.openaiApiKey, project: config.openaiProject }),
  definition,
  store,
  (m) => m.startsWith("brain: tool") && console.log(m),
);
await brain.init(config.agentId);

const heard = process.argv[2] ?? "What's on my calendar for the rest of today? Put it on the screen.";
const started = Date.now();
const secs = () => Math.round((Date.now() - started) / 1000);
let last: string | null = null;
const answer = await brain.ask(
  `[Heard] ${heard}`,
  makeTools({
    store,
    timeZone: config.timeZone,
    now: Date.now,
    userWords: heard,
    previousAssistant: null,
    cameraOn: () => false,
    takePhoto: () => Promise.reject(new Error("no camera in this script")),
    apps,
    pages,
    onPage: (page) => {
      last = page.id;
      console.log(`page "${page.title}" (${page.id}) after ${secs()}s`);
    },
  }),
);
console.log(`DESKU (${secs()}s): ${answer}`);
if (last) {
  const out = join(dir, "page.html");
  writeFileSync(out, withKit(pages.html(last)!));
  console.log(`open ${out}`);
}
