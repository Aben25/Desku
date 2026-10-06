import { createHash, randomUUID } from "node:crypto";
import OpenAI from "openai";
import type { AgentCreateParams, AgentSessionEvent } from "openai/resources/beta/agents/agents";
import type { Store } from "../store/Store.ts";
import type { ToolRunner } from "../engine/tools.ts";

export interface Brain {
  /**
   * Runs one turn and resolves with what Desku should say. `onProgress` gets text the agent
   * wrote before it went off to call tools ("Sure, hold it up to the camera"), with the names of
   * the tools it's about to run, so the voice can say it while they run.
   */
  ask(text: string, tools: ToolRunner, onProgress?: Progress): Promise<string>;
}

export type Progress = (text: string, pendingTools: string[]) => void;

// App questions (calendar + email) can take several tool rounds; GPT-Live fills the wait.
const TURN_TIMEOUT_MS = 150_000;

/**
 * Desku's brain is one long-lived Agents API session created from the saved "Desku" agent.
 * Voice sessions come and go (each is billed per minute), but this session keeps the whole
 * conversation, so Desku remembers context across them. Turns are run one at a time.
 */
export class AgentBrain implements Brain {
  private queue: Promise<unknown> = Promise.resolve();
  private readonly definitionHash: string;

  constructor(
    private readonly client: OpenAI,
    private readonly definition: AgentCreateParams,
    private readonly store: Store,
    private readonly log: (msg: string) => void = console.log,
  ) {
    this.definitionHash = createHash("sha256").update(JSON.stringify(definition)).digest("hex").slice(0, 16);
  }

  /** Makes sure the saved agent exists and matches desku-agent.json. */
  async init(agentIdFromEnv?: string) {
    const link = this.store.agentLink();
    const agentId = agentIdFromEnv || link.agentId;
    if (agentId && link.agentId === agentId && link.definitionHash === this.definitionHash) {
      this.log(`brain: agent ${agentId} is up to date`);
      return;
    }
    if (agentId) {
      await this.client.beta.agents.update(agentId, this.definition);
      this.log(`brain: synced agent ${agentId} to desku-agent.json`);
      this.store.setAgentLink({ agentId, definitionHash: this.definitionHash });
    } else {
      const agent = await this.client.beta.agents.create(this.definition);
      this.log(`brain: created agent ${agent.id}`);
      this.store.setAgentLink({ agentId: agent.id, definitionHash: this.definitionHash });
    }
  }

  ask(text: string, tools: ToolRunner, onProgress?: Progress): Promise<string> {
    const run = this.queue.then(() => this.runWithRecovery(text, tools, onProgress));
    this.queue = run.catch(() => {});
    return run;
  }

  private async runWithRecovery(text: string, tools: ToolRunner, onProgress?: Progress) {
    try {
      return await this.run(text, tools, onProgress);
    } catch (e) {
      // A deleted, expired or failed session can't take more turns: start a fresh one once.
      if (e instanceof SessionGone && this.store.agentLink().sessionId) {
        this.log(`brain: session unusable (${e.message}); starting a new one`);
        this.store.setAgentLink({ sessionId: null, sessionHash: null });
        return this.run(text, tools, onProgress);
      }
      throw e;
    }
  }

  private async run(text: string, tools: ToolRunner, onProgress?: Progress): Promise<string> {
    const link = this.store.agentLink();
    if (!link.agentId) throw new Error("brain not initialised");
    const abort = new AbortController();
    const timer = setTimeout(() => abort.abort(), TURN_TIMEOUT_MS);
    try {
      let sessionId = link.sessionHash === this.definitionHash ? link.sessionId : null;
      let stream: AsyncIterable<AgentSessionEvent>;
      if (!sessionId) {
        stream = await this.client.beta.agents.sessions.create(
          {
            agent_id: link.agentId,
            environment: { type: "none" },
            input: text,
            stream: true,
            metadata: { app: "desku-server" },
          },
          { signal: abort.signal },
        );
      } else {
        // Subscribe before sending so the turn's first events aren't missed.
        stream = await this.client.beta.agents.sessions.events
          .stream(sessionId, { signal: abort.signal })
          .catch((e) => rethrowGone(e));
        await this.client.beta.agents.sessions.events
          .create(sessionId, {
            events: [{ type: "agent.session.input.message", input: [{ role: "user", content: [{ type: "input_text", text }] }] }],
            "Idempotency-Key": randomUUID(),
          })
          .catch((e) => rethrowGone(e));
      }
      return await this.follow(stream, tools, onProgress, (id) => {
        sessionId = id;
        this.store.setAgentLink({ sessionId: id, sessionHash: this.definitionHash });
        this.log(`brain: session ${id}`);
      });
    } catch (e) {
      if (abort.signal.aborted) throw new Error("brain turn timed out");
      throw e;
    } finally {
      clearTimeout(timer);
      abort.abort();
    }
  }

  private async follow(
    stream: AsyncIterable<AgentSessionEvent>,
    tools: ToolRunner,
    onProgress: Progress | undefined,
    onSession: (id: string) => void,
  ): Promise<string> {
    const answered = new Set<string>();
    const said: string[] = [];
    let unsent = ""; // text written since the last tool call; final unless more tools follow

    for await (const event of stream) {
      switch (event.type) {
        case "agent.session.created":
          onSession(event.session.id);
          break;
        case "agent.session.turn.output_text.done":
          // Keep only the latest message: earlier ones were "still checking…" updates, and
          // gluing them together made Desku say all of them in one long answer.
          if (unsent) said.push(unsent);
          unsent = event.text;
          break;
        case "agent.session.requires_action": {
          const session = event.session;
          if (unsent) {
            const pending = session.required_actions.flatMap((a) => (a.type === "function_call" ? [a.name] : []));
            onProgress?.(unsent, pending);
            said.push(unsent);
            unsent = "";
          }
          for (const action of session.required_actions) {
            if (action.type !== "function_call" || answered.has(action.call_id)) continue;
            answered.add(action.call_id);
            const args = typeof action.arguments === "string" ? safeJson(action.arguments) : (action.arguments ?? {});
            const result = await tools(action.name, args as Record<string, unknown>);
            this.log(`brain: tool ${action.name} → ${result.ok ? "ok" : `error: ${result.error}`}`);
            await this.client.beta.agents.sessions.events.create(session.id, {
              events: [
                result.ok
                  ? { type: "agent.session.input.tool_result", turn_id: action.turn_id, call_id: action.call_id, success: true, output: result.output }
                  : { type: "agent.session.input.tool_result", turn_id: action.turn_id, call_id: action.call_id, success: false, error: result.error },
              ],
              "Idempotency-Key": `tool-${action.call_id}`,
            });
          }
          break;
        }
        case "agent.session.turn.completed": {
          const answer = unsent || said.at(-1) || "";
          this.log(`brain: answer: ${answer}`);
          return answer;
        }
        case "agent.session.turn.failed":
        case "agent.session.turn.cancelled":
          throw new Error(`${event.type}: ${JSON.stringify(event.turn.error ?? event.turn.status)}`);
        case "agent.session.failed":
          throw new SessionGone(`session failed: ${JSON.stringify(event.session.error)}`);
        case "error":
          throw new Error(`agent stream error: ${JSON.stringify(event)}`);
      }
    }
    throw new Error("agent stream closed before the turn finished");
  }
}

class SessionGone extends Error {}

function rethrowGone(e: unknown): never {
  if (e instanceof OpenAI.NotFoundError || e instanceof OpenAI.ConflictError) throw new SessionGone(e.message);
  throw e;
}

function safeJson(s: string): unknown {
  try {
    return JSON.parse(s);
  } catch {
    return {};
  }
}
