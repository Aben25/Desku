import type { Store } from "../store/Store.ts";
import { localTime } from "./tools.ts";

const esc = (s: string) => s.replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" })[c]!);

/**
 * The "Today" screen, built straight from the store (no model call, so it's instant): to-dos,
 * focus with its check-in countdown, and saved memories. Uses the page kit's classes.
 */
export function renderToday(store: Store, timeZone: string, now = Date.now()): string {
  const todos = store.todos();
  const open = todos.filter((t) => !t.done);
  const done = todos.filter((t) => t.done).slice(-5);
  const focus = store.focus();
  const next = store.pendingCheckins().find((c) => c.dueAt > now);
  const memories = store.memories();
  const day = localTime(now, timeZone).replace(/ at .*$/, "");

  const todoItems = open.length
    ? open.map((t) => `<li><b>${esc(t.text)}</b></li>`).join("")
    : `<li class="muted">Nothing on the list. Say "add … to my to-dos".</li>`;
  const doneItems = done.map((t) => `<li class="muted"><s>${esc(t.text)}</s> <span class="badge ok">done</span></li>`).join("");

  return `<section class="hero">
  <div class="eyebrow">${esc(day)}</div>
  <h1>Today</h1>
  <p class="sub">${open.length} to-do${open.length === 1 ? "" : "s"} open${focus ? ` · focused on ${esc(focus.task)}` : ""}</p>
</section>
${focus ? `<div class="card accent">
  <div class="eyebrow">Working on</div>
  <h2>${esc(focus.task)}</h2>
  ${next ? `<div class="countdown" data-to="${new Date(next.dueAt).toISOString()}"><span class="dk-label">Check-in in</span></div>` : ""}
</div>` : ""}
<div class="card">
  <h3>To-dos</h3>
  <ul class="list">${todoItems}${doneItems}</ul>
</div>
${memories.length ? `<div class="card">
  <h3>Remembering</h3>
  <ul class="list">${memories.slice(-5).map((m) => `<li>${esc(m.text)}</li>`).join("")}</ul>
</div>` : ""}`;
}
