import assert from "node:assert/strict";
import { mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { test } from "node:test";
import { Apps, connectLinks } from "../src/apps/Apps.ts";
import { makeTools } from "../src/engine/tools.ts";
import { Store } from "../src/store/Store.ts";

test("connect links are found and cleaned", () => {
  const json = JSON.stringify({
    results: { googlecalendar: { redirect_url: "https://connect.composio.dev/link/lk_ABC", note: "Open [Connect](https://connect.composio.dev/link/lk_ABC)." } },
    other: "https://example.com/page",
  });
  assert.deepEqual(connectLinks(json), ["https://connect.composio.dev/link/lk_ABC"]);
});

test("Composio meta tools run through the session; links reach the screen; others still work", async () => {
  const store = new Store(mkdtempSync(join(tmpdir(), "desku-apps-")));
  const executed: unknown[] = [];
  const fakeComposio = {
    create: async () => ({
      sessionId: "trs_1",
      tools: async () => [
        { type: "function", function: { name: "COMPOSIO_SEARCH_TOOLS", description: "search", parameters: { type: "object" } } },
        { type: "function", function: { name: "COMPOSIO_MANAGE_CONNECTIONS", description: "connect", parameters: { type: "object" } } },
        { type: "function", function: { name: "COMPOSIO_REMOTE_BASH_TOOL", description: "bash", parameters: { type: "object" } } },
      ],
      execute: async (name: string, args: unknown) => {
        executed.push([name, args]);
        return name === "COMPOSIO_MANAGE_CONNECTIONS"
          ? { data: { link: "https://connect.composio.dev/link/lk_X" }, error: null, logId: "log_1" }
          : { data: {}, error: "rate limited", logId: "log_2" };
      },
    }),
    sessions: { use: async () => { throw new Error("gone"); } },
  };
  const apps = new Apps(fakeComposio as any, store, "desku-owner", () => {});
  const defs = await apps.init();
  assert.deepEqual(defs.map((d) => d.name), ["COMPOSIO_SEARCH_TOOLS", "COMPOSIO_MANAGE_CONNECTIONS"], "sandbox tools skipped");
  assert.equal(store.agentLink().appsSessionId, "trs_1");

  const links: string[] = [];
  const tools = makeTools({
    store, timeZone: "UTC", now: Date.now, userWords: "", previousAssistant: null,
    cameraOn: () => false, takePhoto: async () => "", apps, onLink: (u) => links.push(u),
  });
  const connect: any = await tools("COMPOSIO_MANAGE_CONNECTIONS", { toolkits: ["googlecalendar"] });
  assert.equal(connect.ok, true);
  assert.deepEqual(links, ["https://connect.composio.dev/link/lk_X"]);
  const failed: any = await tools("COMPOSIO_SEARCH_TOOLS", {});
  assert.equal(failed.ok, false);
  assert.match(failed.error, /rate limited \(Composio log log_2\)/);
  assert.equal((await tools("COMPOSIO_REMOTE_BASH_TOOL", {}) as any).ok, false, "skipped tools aren't runnable");
  assert.equal((await tools("get_desk_status", {}) as any).ok, true);
});
