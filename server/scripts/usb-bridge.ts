// For a desk phone with no internet of its own: forwards it over USB to a remote engine.
// The phone keeps using ws://localhost:8787 (via `adb reverse tcp:8787 tcp:8787`); this relays
// the device WebSocket and page/API requests to ENGINE (default https://desku-engine.fly.dev).
// Usage: npx tsx scripts/usb-bridge.ts [https://your-engine]
import { createServer } from "node:http";
import WebSocket, { WebSocketServer } from "ws";

const engine = (process.argv[2] ?? process.env.ENGINE ?? "https://desku-engine.fly.dev").replace(/\/$/, "");
const wsEngine = engine.replace(/^http/, "ws");
const port = Number(process.env.PORT ?? 8787);

const http = createServer(async (req, res) => {
  try {
    const body = req.method === "GET" || req.method === "HEAD" ? undefined : await new Response(req as any).arrayBuffer();
    const up = await fetch(engine + req.url, {
      method: req.method,
      headers: { ...(req.headers.authorization ? { authorization: req.headers.authorization } : {}), "content-type": req.headers["content-type"] ?? "" },
      body,
    });
    res.writeHead(up.status, { "content-type": up.headers.get("content-type") ?? "text/plain", ...(up.headers.get("content-security-policy") ? { "content-security-policy": up.headers.get("content-security-policy")! } : {}) });
    res.end(Buffer.from(await up.arrayBuffer()));
  } catch (e) {
    res.writeHead(502).end(`bridge: ${(e as Error).message}`);
  }
});

const wss = new WebSocketServer({ noServer: true, maxPayload: 8 * 1024 * 1024 });
http.on("upgrade", (req, socket, head) => {
  wss.handleUpgrade(req, socket, head, (phone) => {
    const upstream = new WebSocket(wsEngine + req.url, { maxPayload: 8 * 1024 * 1024 });
    const pending: Array<[WebSocket.RawData, boolean]> = [];
    console.log(`phone connected → ${wsEngine}${(req.url ?? "").replace(/token=[^&]+/, "token=…")}`);
    phone.on("message", (data, isBinary) => {
      if (upstream.readyState === WebSocket.OPEN) upstream.send(data, { binary: isBinary });
      else pending.push([data, isBinary]);
    });
    upstream.on("open", () => pending.splice(0).forEach(([d, b]) => upstream.send(d, { binary: b })));
    upstream.on("message", (data, isBinary) => phone.readyState === WebSocket.OPEN && phone.send(data, { binary: isBinary }));
    upstream.on("unexpected-response", (_r, resp) => { console.log(`engine refused: ${resp.statusCode}`); phone.close(4001, `engine ${resp.statusCode}`); });
    upstream.on("close", (code, reason) => { console.log(`engine closed ${code}`); if (phone.readyState === WebSocket.OPEN) phone.close(code === 1005 ? 1000 : code, reason); });
    upstream.on("error", (e) => console.log(`engine error: ${e.message}`));
    phone.on("close", () => upstream.terminate());
    phone.on("error", (e) => console.log(`phone error: ${e.message}`));
  });
});

http.listen(port, () => console.log(`USB bridge on :${port} → ${engine}`));
