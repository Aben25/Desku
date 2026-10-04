import assert from "node:assert/strict";
import { mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { test } from "node:test";
import { PAGE_HEADERS, PAGE_ID, Pages } from "../src/pages/Pages.ts";

const dir = () => mkdtempSync(join(tmpdir(), "desku-pages-"));

test("pages: save, read back, update in place, survive a restart", () => {
  let clock = 1000;
  const data = dir();
  const pages = new Pages(data, () => clock);
  const page = pages.save("Open tickets", "<h1>3 open</h1>");
  assert.match(page.id, PAGE_ID);
  assert.equal(pages.html(page.id), "<h1>3 open</h1>");

  clock = 2000;
  const updated = pages.save("Open tickets (2)", "<h1>2 open</h1>", page.id);
  assert.equal(updated.id, page.id);
  assert.equal(updated.createdAt, 1000);
  assert.equal(updated.updatedAt, 2000);

  const reopened = new Pages(data);
  assert.deepEqual(reopened.list().map((p) => p.title), ["Open tickets (2)"]);
  assert.equal(reopened.html(page.id), "<h1>2 open</h1>");
});

test("pages: rejects empty, oversized, and unknown ids", () => {
  const pages = new Pages(dir());
  assert.throws(() => pages.save("x", "   "), /empty/);
  assert.throws(() => pages.save("x", "a".repeat(600 * 1024)), /over/);
  assert.throws(() => pages.save("x", "<p>hi</p>", "AAAAAAAAAAAAAAAAAAAAAA"), /no page/);
  assert.equal(pages.html("../index"), null);
});

test("pages: keeps the newest 50 and deletes", () => {
  let clock = 0;
  const pages = new Pages(dir(), () => ++clock);
  const first = pages.save("p0", "<p>0</p>");
  for (let i = 1; i <= 50; i++) pages.save(`p${i}`, `<p>${i}</p>`);
  assert.equal(pages.list().length, 50);
  assert.equal(pages.html(first.id), null);
  const newest = pages.list()[0];
  assert.equal(newest.title, "p50");
  assert.ok(pages.delete(newest.id));
  assert.equal(pages.delete(newest.id), false);
});

test("pages: served sandboxed, with no way to send data out", () => {
  const csp = PAGE_HEADERS["Content-Security-Policy"];
  assert.match(csp, /sandbox allow-scripts/);
  assert.doesNotMatch(csp, /allow-same-origin/);
  assert.match(csp, /connect-src 'none'/);
  assert.match(csp, /form-action 'none'/);
});

test("pages: the UI kit is added to every page as it's served", async () => {
  const { withKit } = await import("../src/pages/Pages.ts");
  const full = withKit("<!doctype html><html><head><title>t</title><style>.mine{}</style></head><body><p>x</p></body></html>");
  // Kit styles come before the page's own styles, so the page can override them.
  assert.ok(full.indexOf("data-desku-kit") < full.indexOf(".mine{}"));
  assert.ok(full.indexOf("<script data-desku-kit>") < full.indexOf("</body>"));
  // A bare fragment still gets both.
  const bare = withKit("<div class=\"card\">hi</div>");
  assert.match(bare, /^<!doctype html><meta name="viewport"/);
  assert.equal(full.match(/<!doctype/gi)?.length, 1);
  assert.match(bare, /<script data-desku-kit>[\s\S]*<\/script>$/);
});
