// Connect one app for Desku's owner: prints a Connect Link, then waits until it's approved.
// Usage: npx tsx --env-file=.env scripts/connect-app.ts googlecalendar
import { Composio } from "@composio/core";
import { loadConfig } from "../src/config.ts";
import { Store } from "../src/store/Store.ts";

const toolkit = process.argv[2];
if (!toolkit) {
  console.error("Usage: scripts/connect-app.ts <toolkit-slug>   (e.g. googlecalendar, gmail, slack)");
  process.exit(1);
}
const config = loadConfig();
const store = new Store(config.dataDir);
const composio = new Composio({ apiKey: config.composioApiKey });
const saved = store.agentLink().appsSessionId;
const session = saved ? await composio.sessions.use(saved) : await composio.create(config.composioUserId);
if (!saved) store.setAgentLink({ appsSessionId: session.sessionId });

const request = await session.authorize(toolkit);
console.log(`Open this link to connect ${toolkit} for ${config.composioUserId}:\n\n  ${request.redirectUrl}\n`);
console.log("Waiting for approval (up to 15 minutes)…");
const account = await request.waitForConnection(15 * 60_000);
console.log(`Connected: ${toolkit} → ${account.id} (${account.status})`);
