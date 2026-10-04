import { mkdirSync, readFileSync, renameSync, writeFileSync } from "node:fs";
import { join } from "node:path";

export interface Memory {
  id: string;
  text: string;
  createdAt: number;
}

/** What the user said they're working on. Desku follows up on it. */
export interface Focus {
  task: string;
  startedAt: number;
  doneAt?: number;
  outcome?: string;
}

export interface Checkin {
  id: string;
  dueAt: number;
  about: string;
  createdAt: number;
  firedAt?: number;
}

/** Which saved Agents API agent and session hold Desku's brain. */
export interface AgentLink {
  agentId: string | null;
  /** Hash of the agent definition the saved agent was last synced to. */
  definitionHash: string | null;
  sessionId: string | null;
  /** Definition hash the session was created with; sessions don't pick up later agent edits. */
  sessionHash: string | null;
  /** Composio session for the user's apps. */
  appsSessionId: string | null;
}

interface State {
  memories: Memory[];
  focus: Focus | null;
  focusHistory: Focus[];
  checkins: Checkin[];
  agent: AgentLink;
}

const EMPTY: State = {
  memories: [],
  focus: null,
  focusHistory: [],
  checkins: [],
  agent: { agentId: null, definitionHash: null, sessionId: null, sessionHash: null, appsSessionId: null },
};

/**
 * Everything Desku keeps between conversations, in one JSON file. Conversations, audio and
 * photos are never written here.
 */
export class Store {
  private state: State;
  private readonly file: string;
  private listeners: Array<() => void> = [];

  constructor(dataDir: string, private readonly clock: () => number = Date.now) {
    mkdirSync(dataDir, { recursive: true });
    this.file = join(dataDir, "desku.json");
    this.state = this.load();
  }

  onChange(fn: () => void) {
    this.listeners.push(fn);
  }

  // Memories

  memories(): Memory[] {
    return this.state.memories;
  }

  addMemory(text: string): Memory {
    const clean = text.trim().slice(0, 500);
    if (!clean) throw new Error("empty memory");
    const next = Math.max(0, ...this.state.memories.map((m) => Number(m.id.slice(1)) || 0)) + 1;
    const memory = { id: `m${next}`, text: clean, createdAt: this.clock() };
    this.save({ ...this.state, memories: [...this.state.memories, memory] });
    return memory;
  }

  deleteMemory(id: string): boolean {
    const remaining = this.state.memories.filter((m) => m.id !== id);
    if (remaining.length === this.state.memories.length) return false;
    this.save({ ...this.state, memories: remaining });
    return true;
  }

  clearMemories() {
    this.save({ ...this.state, memories: [] });
  }

  // Focus

  focus(): Focus | null {
    return this.state.focus;
  }

  recentFocus(n = 5): Focus[] {
    return this.state.focusHistory.slice(-n);
  }

  setFocus(task: string): Focus {
    const clean = task.trim().slice(0, 300);
    if (!clean) throw new Error("empty focus");
    const history = this.state.focus ? [...this.state.focusHistory, this.state.focus] : this.state.focusHistory;
    const focus = { task: clean, startedAt: this.clock() };
    this.save({ ...this.state, focus, focusHistory: history.slice(-50) });
    return focus;
  }

  finishFocus(outcome: string): Focus | null {
    const current = this.state.focus;
    if (!current) return null;
    const done = { ...current, doneAt: this.clock(), outcome: outcome.trim().slice(0, 300) };
    this.save({ ...this.state, focus: null, focusHistory: [...this.state.focusHistory, done].slice(-50) });
    return done;
  }

  // Check-ins

  pendingCheckins(): Checkin[] {
    return this.state.checkins.filter((c) => !c.firedAt).sort((a, b) => a.dueAt - b.dueAt);
  }

  addCheckin(dueAt: number, about: string): Checkin {
    const checkin = {
      id: `c${this.clock().toString(36)}${Math.random().toString(36).slice(2, 6)}`,
      dueAt,
      about: about.trim().slice(0, 300),
      createdAt: this.clock(),
    };
    this.save({ ...this.state, checkins: [...this.state.checkins, checkin] });
    return checkin;
  }

  markCheckinFired(id: string) {
    const fired = this.clock();
    // Keep only the last day of fired check-ins so the file doesn't grow forever.
    const checkins = this.state.checkins
      .map((c) => (c.id === id ? { ...c, firedAt: fired } : c))
      .filter((c) => !c.firedAt || fired - c.firedAt < 24 * 3600_000);
    this.save({ ...this.state, checkins });
  }

  /** Cancels pending check-ins matching `which` (all of them by default). Returns how many. */
  cancelCheckins(which: (c: Checkin) => boolean = () => true): number {
    const cancel = (c: Checkin) => !c.firedAt && which(c);
    const count = this.state.checkins.filter(cancel).length;
    this.save({ ...this.state, checkins: this.state.checkins.filter((c) => !cancel(c)) });
    return count;
  }

  // Agents API link

  agentLink(): AgentLink {
    return this.state.agent;
  }

  setAgentLink(patch: Partial<AgentLink>) {
    this.save({ ...this.state, agent: { ...this.state.agent, ...patch } });
  }

  private save(next: State) {
    // Write-then-rename so a crash mid-write can't leave a half file behind.
    const tmp = `${this.file}.tmp`;
    writeFileSync(tmp, JSON.stringify(next, null, 2));
    renameSync(tmp, this.file);
    this.state = next;
    for (const fn of this.listeners) fn();
  }

  private load(): State {
    try {
      const saved = JSON.parse(readFileSync(this.file, "utf8"));
      return { ...EMPTY, ...saved, agent: { ...EMPTY.agent, ...saved.agent } };
    } catch {
      return structuredClone(EMPTY);
    }
  }
}
