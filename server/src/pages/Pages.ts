import { randomBytes } from "node:crypto";
import { mkdirSync, readFileSync, renameSync, rmSync, writeFileSync } from "node:fs";
import { join } from "node:path";

export interface Page {
  id: string;
  title: string;
  createdAt: number;
  updatedAt: number;
}

const MAX_HTML_BYTES = 512 * 1024;
const KEEP = 50;

/**
 * Web pages Desku writes itself (ticket boards, schedules, summaries) and shows on the desk
 * screen. Each page is one self-contained HTML file under data/pages/, served at /p/<id>.
 * The ID is 128 random bits, so a page's link is its only key.
 *
 * Pages often carry text from emails, tickets and the web, so they are served with a sandbox
 * CSP (see PAGE_HEADERS): their scripts run in an opaque origin, can't read the desk token or
 * call this server, and can't send anything out.
 */
export class Pages {
  private readonly dir: string;
  private readonly index: string;
  private pages: Page[];

  constructor(dataDir: string, private readonly clock: () => number = Date.now) {
    this.dir = join(dataDir, "pages");
    mkdirSync(this.dir, { recursive: true });
    this.index = join(this.dir, "index.json");
    try {
      this.pages = JSON.parse(readFileSync(this.index, "utf8"));
    } catch {
      this.pages = [];
    }
  }

  /** Newest first. */
  list(): Page[] {
    return [...this.pages].sort((a, b) => b.updatedAt - a.updatedAt);
  }

  get(id: string): Page | undefined {
    return this.pages.find((p) => p.id === id);
  }

  html(id: string): string | null {
    if (!this.get(id)) return null;
    try {
      return readFileSync(this.file(id), "utf8");
    } catch {
      return null;
    }
  }

  /** Creates a page, or replaces one when `id` names an existing page. */
  save(title: string, html: string, id?: string | null): Page {
    const cleanTitle = title.trim().slice(0, 120) || "Desku page";
    if (!html.trim()) throw new Error("html is empty");
    if (Buffer.byteLength(html) > MAX_HTML_BYTES) throw new Error(`html is over ${MAX_HTML_BYTES / 1024} KB`);
    const now = this.clock();
    const existing = id ? this.get(id) : undefined;
    if (id && !existing) throw new Error(`no page with id ${id}`);
    const page = existing
      ? { ...existing, title: cleanTitle, updatedAt: now }
      : { id: randomBytes(16).toString("base64url"), title: cleanTitle, createdAt: now, updatedAt: now };
    writeAtomic(this.file(page.id), html);
    const rest = this.pages.filter((p) => p.id !== page.id);
    const kept = [page, ...rest].sort((a, b) => b.updatedAt - a.updatedAt);
    for (const old of kept.slice(KEEP)) rmSync(this.file(old.id), { force: true });
    this.pages = kept.slice(0, KEEP);
    writeAtomic(this.index, JSON.stringify(this.pages, null, 2));
    return page;
  }

  delete(id: string): boolean {
    if (!this.get(id)) return false;
    rmSync(this.file(id), { force: true });
    this.pages = this.pages.filter((p) => p.id !== id);
    writeAtomic(this.index, JSON.stringify(this.pages, null, 2));
    return true;
  }

  private file(id: string) {
    return join(this.dir, `${id}.html`);
  }
}

/** IDs are base64url from randomBytes(16): exactly 22 characters. */
export const PAGE_ID = /^[A-Za-z0-9_-]{22}$/;

/**
 * Headers for serving a page. `sandbox` without allow-same-origin gives the page an opaque
 * origin (no access to the agent page's localStorage token). connect-src/form-action 'none'
 * and images limited to data: URIs mean it can't phone home. Scripts and styles may come from
 * two CDNs so Desku can use a chart library.
 */
export const PAGE_HEADERS = {
  "Content-Type": "text/html; charset=utf-8",
  "Content-Security-Policy": [
    // Links (a ticket, a doc) open in a normal tab rather than staying sandboxed.
    "sandbox allow-scripts allow-popups allow-popups-to-escape-sandbox",
    "default-src 'none'",
    "script-src 'unsafe-inline' https://cdn.jsdelivr.net https://cdnjs.cloudflare.com",
    "style-src 'unsafe-inline' https://fonts.googleapis.com",
    "font-src https://fonts.gstatic.com",
    "img-src data: blob:",
    "connect-src 'none'",
    "form-action 'none'",
    "base-uri 'none'",
  ].join("; "),
  "X-Content-Type-Options": "nosniff",
  "Referrer-Policy": "no-referrer",
  "Cache-Control": "no-store",
};

const KIT_CSS = readFileSync(new URL("./kit.css", import.meta.url), "utf8");
const KIT_JS = readFileSync(new URL("./kit.js", import.meta.url), "utf8");
const KIT_HEAD =
  `<meta name="viewport" content="width=device-width, initial-scale=1">` +
  `<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=DM+Mono:wght@400;500&family=Figtree:wght@400;500;600;700&display=swap">` +
  `<style data-desku-kit>${KIT_CSS}</style>`;

/**
 * Adds the Desku UI kit to a page as it's served: kit styles first in <head> (so the page's own
 * styles win), kit script at the end of <body>. Desku then only writes the HTML and data.
 */
export function withKit(html: string): string {
  const head = /<head[^>]*>/i.exec(html);
  let out = head ? html.slice(0, head.index + head[0].length) + KIT_HEAD + html.slice(head.index + head[0].length) : KIT_HEAD + html;
  const script = `<script data-desku-kit>${KIT_JS}</script>`;
  const end = out.search(/<\/body>/i);
  out = end >= 0 ? out.slice(0, end) + script + out.slice(end) : out + script;
  // Desku usually writes a fragment (title + body); without a doctype it would render in quirks mode.
  return /^\s*<!doctype/i.test(out) ? out : `<!doctype html>${out}`;
}

function writeAtomic(file: string, body: string) {
  const tmp = `${file}.tmp`;
  writeFileSync(tmp, body);
  renameSync(tmp, file);
}
