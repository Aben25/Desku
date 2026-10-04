/**
 * Minimal AgentPhone REST client (https://agentphone.ai/skills.md): outbound AI calls from
 * Desku's own number. With a systemPrompt the call runs on AgentPhone's hosted voice AI, so
 * no webhook is needed; we poll for the transcript afterwards.
 */
export interface CallRequest {
  toNumber: string;
  systemPrompt: string;
  initialGreeting: string;
}

export interface CallResult {
  id: string;
  status: string;
  transcript: Array<{ role: "user" | "agent"; content: string }>;
}

export interface PhoneCaller {
  call(req: CallRequest): Promise<CallResult>;
}

const BASE = "https://api.agentphone.ai";

export class AgentPhone implements PhoneCaller {
  private sender: Promise<{ agentId: string; numberId: string }> | null = null;

  constructor(
    private readonly apiKey: string,
    /** Desku's AgentPhone number, E.164. The call goes out from this number. */
    private readonly fromNumber: string,
    private readonly log: (msg: string) => void = console.log,
    private readonly fetchImpl: typeof fetch = fetch,
    private readonly pollMs = 5_000,
  ) {}

  /** Places the call and resolves once it has ended, with the transcript. */
  async call(req: CallRequest): Promise<CallResult> {
    const { agentId, numberId } = await this.resolveSender();
    const started = await this.request<{ id: string }>("POST", "/v1/calls", {
      agentId,
      fromNumberId: numberId,
      toNumber: req.toNumber,
      systemPrompt: req.systemPrompt,
      initialGreeting: req.initialGreeting,
    });
    this.log(`phone: calling ${mask(req.toNumber)} (call ${started.id})`);
    const deadline = Date.now() + 20 * 60_000;
    for (;;) {
      await new Promise((r) => setTimeout(r, this.pollMs));
      const call = await this.request<{ status: string }>("GET", `/v1/calls/${started.id}`);
      if (call.status === "completed" || call.status === "failed") {
        const t = await this.request<{ transcript?: CallResult["transcript"] }>("GET", `/v1/calls/${started.id}/transcript`);
        this.log(`phone: call ${started.id} ${call.status}`);
        return { id: started.id, status: call.status, transcript: t.transcript ?? [] };
      }
      if (Date.now() > deadline) {
        await this.request("POST", `/v1/calls/${started.id}/end`).catch(() => {});
        throw new Error(`call ${started.id} still ${call.status} after 20 minutes; ended it`);
      }
    }
  }

  /** Finds the agent and number id that own Desku's number. */
  private resolveSender() {
    this.sender ??= (async () => {
      const numbers = await this.request<{ data: Array<{ id: string; phoneNumber: string; agentId: string | null }> }>(
        "GET",
        "/v1/numbers?limit=100",
      );
      const mine = numbers.data.find((n) => n.phoneNumber === this.fromNumber);
      if (!mine) throw new Error(`${this.fromNumber} isn't a number on this AgentPhone account`);
      if (!mine.agentId) throw new Error(`${this.fromNumber} isn't attached to an AgentPhone agent`);
      return { agentId: mine.agentId, numberId: mine.id };
    })();
    this.sender.catch(() => (this.sender = null));
    return this.sender;
  }

  private async request<T>(method: string, path: string, body?: object): Promise<T> {
    const res = await this.fetchImpl(BASE + path, {
      method,
      headers: { Authorization: `Bearer ${this.apiKey}`, "Content-Type": "application/json" },
      body: body ? JSON.stringify(body) : undefined,
    });
    const text = await res.text();
    if (!res.ok) {
      const hint = res.status === 402 ? " (outbound calls need a payment method on the AgentPhone account)" : "";
      throw new Error(`AgentPhone ${method} ${path} → ${res.status}${hint}: ${text.slice(0, 300)}`);
    }
    return (text ? JSON.parse(text) : {}) as T;
  }
}

/** For logs: keep the last 4 digits only. */
export const mask = (n: string) => n.replace(/\d(?=\d{4})/g, "•");
