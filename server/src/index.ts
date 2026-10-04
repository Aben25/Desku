import { readFileSync } from "node:fs";
import { createServer } from "node:http";
import { extname, join, normalize } from "node:path";
import { timingSafeEqual } from "node:crypto";
import { Composio } from "@composio/core";
import OpenAI from "openai";
import { Apps } from "./apps/Apps.ts";
import { WebSocketServer } from "ws";
import { AgentBrain } from "./brain/AgentBrain.ts";
import { loadConfig } from "./config.ts";
import { Desk } from "./engine/Desk.ts";
import { LiveClient } from "./live/LiveClient.ts";
import { AgentPhone } from "./phone/AgentPhone.ts";
import { Store } from "./store/Store.ts";
import { PAGE_HEADERS, PAGE_ID, Pages, withKit } from "./pages/Pages.ts";

const config = loadConfig();
const store = new Store(config.dataDir);
// Web pages Desku writes for the desk screen, served at /p/<id>.
const pages = new Pages(config.dataDir);
const openai = new OpenAI({ apiKey: config.openaiApiKey, project: config.openaiProject });

const definition = JSON.parse(readFileSync(config.agentDefinition, "utf8"));
// The user's apps: Composio's meta tools join the agent's function tools; this server runs them.
let apps: Apps | null = null;
if (config.composioApiKey) {
  apps = new Apps(new Composio({ apiKey: config.composioApiKey }), store, config.composioUserId);
  definition.tools.push(...(await apps.init()));
} else {
  console.log("apps off (set COMPOSIO_API_KEY to enable)");
}
const brain = new AgentBrain(openai, definition, store);
await brain.init(config.agentId);

const desk = new Desk(
  store,
  brain,
  (instructions) =>
    new LiveClient({
      url: config.liveUrl,
      apiKey: config.openaiApiKey,
      session: {
        model: config.liveModel,
        instructions,
        audio: { output: { voice: config.voice }, format: { type: "audio/pcm", rate: 24000 } },
        delegation: { type: "client" },
      },
    }),
  config,
);
if (apps) desk.enableApps(apps);
desk.enablePages(pages);
if (config.agentPhoneApiKey && config.ownerPhone) {
  desk.enableAwayCalls({
    caller: new AgentPhone(config.agentPhoneApiKey, config.agentPhoneNumber),
    toNumber: config.ownerPhone,
    noReplySeconds: config.awayCallAfterSeconds,
    quietHours: config.quietHours,
  });
  console.log(`away calls on: from ${config.agentPhoneNumber} after ${config.awayCallAfterSeconds}s without a reply`);
} else {
  console.log("away calls off (set AGENTPHONE_API_KEY and OWNER_PHONE to enable)");
}
desk.start();

const expected = Buffer.from(config.deviceToken);
const authorized = (given: string) => {
  const buf = Buffer.from(given);
  return buf.length === expected.length && timingSafeEqual(buf, expected);
};

// Connected apps come from Composio; cache them briefly since the agent page polls.
let appsCache: { at: number; value: unknown } | null = null;
async function appConnections() {
  if (!apps) return { enabled: false, connections: [] };
  if (appsCache && Date.now() - appsCache.at < 30_000) return appsCache.value;
  let value: { enabled: boolean; user: string; connections: Array<{ status: string }>; error?: string };
  try {
    value = { enabled: true, user: config.composioUserId, connections: await apps.connections() };
  } catch (e) {
    value = { enabled: true, user: config.composioUserId, connections: [], error: (e as Error).message };
  }
  // Don't cache while a connection is still being approved, so "connecting" flips to connected
  // as soon as Composio marks it ACTIVE.
  const pending = value.connections.some((c) => c.status === "INITIATED" || c.status === "INITIALIZING");
  appsCache = pending ? null : { at: Date.now(), value };
  return value;
}

/** Everything the agent page shows: who Desku is, what it's connected to, and what it keeps. */
async function overview() {
  const link = store.agentLink();
  return {
    now: Date.now(),
    timeZone: config.timeZone,
    agent: {
      name: definition.name,
      model: definition.model,
      reasoning: definition.reasoning?.effort ?? null,
      agentId: link.agentId,
      sessionId: link.sessionId,
      project: config.openaiProject,
      instructions: definition.instructions,
    },
    voice: { model: config.liveModel, voice: config.voice, idleCloseSeconds: config.idleCloseSeconds },
    phone: { number: config.agentPhoneNumber },
    status: desk.status(),
    tools: (definition.tools as Array<{ type: string; name?: string; description?: string }>).map((t) =>
      t.type === "function"
        ? { name: t.name, kind: apps?.has(t.name!) ? "apps" : "desk", description: t.description ?? "" }
        : { name: t.type, kind: "hosted", description: t.type === "web_search" ? "Searches the web for current facts." : "" },
    ),
    apps: await appConnections(),
    focus: store.focus(),
    focusHistory: store.recentFocus(10).reverse(),
    checkins: store.pendingCheckins(),
    memories: store.memories(),
    pages: pages.list(),
  };
}

function isLocal(req: import("node:http").IncomingMessage) {
  const ip = req.socket.remoteAddress ?? "";
  const host = (req.headers.host ?? "").replace(/:\d+$/, "");
  return ["127.0.0.1", "::1", "::ffff:127.0.0.1"].includes(ip) && ["localhost", "127.0.0.1", "[::1]"].includes(host);
}

// Static pages (public/): the browser test client and the agent page.
const PUBLIC = new URL("../public/", import.meta.url).pathname;
const TYPES: Record<string, string> = { ".html": "text/html", ".js": "text/javascript", ".css": "text/css" };
const http = createServer(async (req, res) => {
  const path = normalize(new URL(req.url ?? "/", "http://x").pathname).replace(/^\/+/, "") || "index.html";
  if (path === "healthz") return void res.end("ok");
  if (path === "agent") {
    res.writeHead(302, { Location: "/agent.html" });
    return void res.end();
  }
  if (path === "api/overview") {
    // Memories and focus are personal: same shared secret as the desk phone.
    if (!authorized((req.headers.authorization ?? "").replace(/^Bearer /, ""))) {
      res.writeHead(401, { "Content-Type": "application/json" });
      return void res.end(JSON.stringify({ error: "wrong or missing token" }));
    }
    res.writeHead(200, { "Content-Type": "application/json", "Cache-Control": "no-store" });
    return void res.end(JSON.stringify(await overview()));
  }
  const pageMatch = /^p\/([^/]+)$/.exec(path);
  if (pageMatch) {
    // The 128-bit ID is the key: the desk screen opens these without the token.
    const html = PAGE_ID.test(pageMatch[1]) ? pages.html(pageMatch[1]) : null;
    if (html === null) return void res.writeHead(404).end("not found");
    res.writeHead(200, PAGE_HEADERS);
    return void res.end(withKit(html));
  }
  const deleteMatch = /^api\/pages\/([^/]+)$/.exec(path);
  if (deleteMatch && req.method === "DELETE") {
    if (!authorized((req.headers.authorization ?? "").replace(/^Bearer /, ""))) return void res.writeHead(401).end();
    return void res.writeHead(pages.delete(deleteMatch[1]) ? 204 : 404).end();
  }
  if (path === "api/apps/connect" && req.method === "POST") {
    // Start connecting an app (Gmail, Google Calendar, Slack…): returns a Composio Connect Link.
    const reply = (status: number, body: unknown) => {
      res.writeHead(status, { "Content-Type": "application/json", "Cache-Control": "no-store" });
      res.end(JSON.stringify(body));
    };
    if (!authorized((req.headers.authorization ?? "").replace(/^Bearer /, ""))) return reply(401, { error: "wrong or missing token" });
    if (!apps) return reply(503, { error: "Composio isn't set up: add COMPOSIO_API_KEY to server/.env" });
    try {
      let raw = "";
      for await (const chunk of req) raw += chunk;
      const { app } = JSON.parse(raw || "{}") as { app?: string };
      const url = await apps.connect(String(app ?? ""));
      appsCache = null; // the new connection shows up as pending on the next overview
      return reply(200, { url });
    } catch (e) {
      return reply(400, { error: (e as Error).message });
    }
  }
  try {
    let body: Buffer | string = readFileSync(join(PUBLIC, path));
    // Opened on this computer (localhost): fill in the desk token so the page opens straight away.
    // Requests from any other host, or through a tunnel with another Host name, still need it.
    if (extname(path) === ".html" && isLocal(req)) {
      body = body.toString().replace("<head>", `<head><script>try{localStorage.setItem("desku.token",${JSON.stringify(config.deviceToken)})}catch(e){}</script>`);
    }
    res.writeHead(200, { "Content-Type": TYPES[extname(path)] ?? "application/octet-stream", "Cache-Control": "no-store" });
    res.end(body);
  } catch {
    res.writeHead(404).end("not found");
  }
});

const wss = new WebSocketServer({ noServer: true, maxPayload: 8 * 1024 * 1024 });
http.on("upgrade", (req, socket, head) => {
  const url = new URL(req.url ?? "/", "http://x");
  if (url.pathname !== "/device" || !authorized(url.searchParams.get("token") ?? "")) {
    socket.end("HTTP/1.1 401 Unauthorized\r\n\r\n");
    return;
  }
  wss.handleUpgrade(req, socket, head, (ws) => {
    console.log(`device connected from ${req.socket.remoteAddress}`);
    desk.attach(ws);
  });
});

http.listen(config.port, () => {
  const page = `http://localhost:${config.port}/agent.html`;
  console.log(`Desku engine on http://localhost:${config.port}  (device socket: /device?token=…)`);
  console.log(`Agent page: ${page}`);
  // Started by hand in a terminal: open the agent page (set OPEN_PAGE=0 to skip).
  if (process.stdout.isTTY && process.env.OPEN_PAGE !== "0" && process.platform === "darwin") {
    import("node:child_process").then(({ spawn }) => spawn("open", [page], { stdio: "ignore", detached: true }).unref());
  }
});

const shutdown = () => {
  desk.stop();
  http.close();
  setTimeout(() => process.exit(0), 1000).unref();
};
process.on("SIGINT", shutdown);
process.on("SIGTERM", shutdown);
