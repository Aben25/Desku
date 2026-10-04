import type { Composio } from "@composio/core";
import type { Store } from "../store/Store.ts";
import type { ToolResult } from "../engine/tools.ts";

export interface FunctionToolDef {
  type: "function";
  name: string;
  description: string;
  parameters: Record<string, unknown>;
}

export interface AppConnection {
  /** Composio toolkit slug, e.g. "googlecalendar". */
  app: string;
  status: string;
  alias: string | null;
  since: string;
}

type Session =Awaited<ReturnType<Composio["create"]>>;

// A desk buddy reads and acts on apps; it doesn't need Composio's remote sandbox tools.
const SKIP = new Set(["COMPOSIO_REMOTE_BASH_TOOL", "COMPOSIO_REMOTE_WORKBENCH"]);
const MAX_OUTPUT_CHARS = 40_000;

/**
 * The user's apps (Calendar, Gmail, Slack, … 500+) through one Composio session. Its meta
 * tools (search, schemas, execute, connect) become function tools on the Desku agent; when
 * the agent calls one, this server executes it, so the Composio key never leaves the server.
 * Desku has one owner, so one stable Composio user ID; the session ID is kept in the store.
 */
export class Apps {
  private session: Session | null = null;
  private names = new Set<string>();

  constructor(
    private readonly composio: Composio,
    private readonly store: Store,
    private readonly userId: string,
    private readonly log: (msg: string) => void = console.log,
  ) {}

  async init(): Promise<FunctionToolDef[]> {
    const saved = this.store.agentLink().appsSessionId;
    if (saved) {
      try {
        this.session = await this.composio.sessions.use(saved);
      } catch (e) {
        this.log(`apps: saved session ${saved} unusable (${(e as Error).message}); creating a new one`);
      }
    }
    if (!this.session) {
      this.session = await this.composio.create(this.userId);
      this.store.setAgentLink({ appsSessionId: this.session.sessionId });
    }
    const tools = (await this.session.tools()) as unknown as Array<{
      function: { name: string; description?: string; parameters?: Record<string, unknown> };
    }>;
    const defs = tools
      .map((t) => t.function)
      .filter((f) => !SKIP.has(f.name))
      .map((f) => ({
        type: "function" as const,
        name: f.name,
        description: (f.description ?? "").trim(),
        parameters: f.parameters ?? { type: "object", properties: {} },
      }));
    this.names = new Set(defs.map((d) => d.name));
    this.log(`apps: session ${this.session.sessionId} for user ${this.userId} (${defs.length} tools)`);
    return defs;
  }

  /** Starts connecting an app and returns the Connect Link to open. */
  async connect(toolkit: string): Promise<string> {
    if (!this.session) throw new Error("apps aren't set up on the server");
    if (!/^[a-z0-9_]{2,64}$/.test(toolkit)) throw new Error("toolkit slugs are lowercase letters, digits and _");
    const request = await this.session.authorize(toolkit);
    if (!request.redirectUrl) throw new Error(`${toolkit} returned no link to open`);
    this.log(`apps: connect link for ${toolkit}`);
    return request.redirectUrl;
  }

  has(name: string) {
    return this.names.has(name);
  }

  /** The apps the owner has connected (or started connecting), for the agent page. */
  async connections(): Promise<AppConnection[]> {
    const res = await this.composio.connectedAccounts.list({ userIds: [this.userId], limit: 100 });
    return res.items.map((a) => ({
      app: a.toolkit.slug,
      status: a.isDisabled ? "DISABLED" : a.status,
      alias: a.alias ?? null,
      since: a.createdAt,
    }));
  }

  /** Runs one meta tool. `onLink` gets any Connect Link so the phone can show it. */
  async run(name: string, args: Record<string, unknown>, onLink?: (url: string) => void): Promise<ToolResult> {
    if (!this.session) return { ok: false, error: "apps aren't set up on the server" };
    try {
      const res = await this.session.execute(name, args);
      this.log(`apps: ${name} → ${res.error ? `error: ${res.error}` : "ok"} (log ${res.logId})`);
      if (res.error) return { ok: false, error: `${res.error} (Composio log ${res.logId})` };
      const json = JSON.stringify(res.data);
      for (const url of connectLinks(json)) onLink?.(url);
      return {
        ok: true,
        output: json.length > MAX_OUTPUT_CHARS ? `${json.slice(0, MAX_OUTPUT_CHARS)}… [truncated]` : json,
      };
    } catch (e) {
      return { ok: false, error: `Composio ${name} failed: ${(e as Error).message}` };
    }
  }
}

/** Connect Links in a tool result: the URLs the user must open to authorize an app. */
export function connectLinks(json: string): string[] {
  // Stop at quotes, whitespace, escapes and closing brackets; drop trailing punctuation
  // (links often arrive inside markdown like "[Connect](https://…).").
  const urls = (json.match(/https:\/\/[^"'\s\\)\]>]+/g) ?? []).map((u) => u.replace(/[.,;:!?]+$/, ""));
  return [...new Set(urls.filter((u) => /connect|auth|oauth|link/i.test(u) && /composio|\/link\//i.test(u)))];
}
