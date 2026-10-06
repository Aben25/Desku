export type Role = "user" | "assistant";

export interface Turn {
  role: Role;
  text: string;
  startMs: number;
}

/**
 * GPT-Live sends transcript fragments for both sides, timed but not split into turns, and user
 * and assistant fragments can interleave (it's full duplex). We fold consecutive fragments from
 * the same side into one turn, and separately keep what the user said since the last
 * delegation, which is the task text for client delegation (the delegation event itself
 * carries no text).
 */
export class Transcript {
  private turns: Turn[] = [];
  private sinceDelegation = "";

  add(role: Role, delta: string, startMs: number) {
    const last = this.turns.at(-1);
    if (last && last.role === role) last.text += delta;
    else this.turns.push({ role, text: delta, startMs });
    if (role === "user") this.sinceDelegation += delta;
    if (this.turns.length > 200) this.turns.splice(0, this.turns.length - 200);
  }

  /** Adds a turn for context only (e.g. a screen tap), not as speech awaiting delegation. */
  note(role: Role, text: string) {
    const pending = this.sinceDelegation;
    this.add(role, text, 0);
    this.sinceDelegation = pending;
  }

  /** User speech since the previous call, then resets. */
  takeUserRequest(): string {
    const text = this.sinceDelegation.trim();
    this.sinceDelegation = "";
    return text;
  }

  /**
   * The last two things Desku said, for the memory policy's "yes to an offer" rule. Two, because
   * the voice model often says a quick "okay, one sec" between the offer and the delegation.
   */
  previousAssistant(): string | null {
    const said = this.turns.filter((t) => t.role === "assistant").slice(-2);
    return said.length ? said.map((t) => t.text.trim()).join(" ") : null;
  }

  /** The user's last [n] turns, joined: a request often spans several ("call my friend" … "yes"). */
  recentUser(n = 4): string {
    return this.turns.filter((t) => t.role === "user").slice(-n).map((t) => t.text.trim()).join(" ");
  }

  recent(n = 20): string {
    return this.turns
      .slice(-n)
      .map((t) => `${t.role === "user" ? "User" : "Desku"}: ${t.text.trim()}`)
      .join("\n");
  }
}
