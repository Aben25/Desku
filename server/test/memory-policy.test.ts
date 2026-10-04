import assert from "node:assert/strict";
import { test } from "node:test";
import { allowsSave } from "../src/brain/MemoryPolicy.ts";

test("explicit requests allow saving", () => {
  assert.ok(allowsSave("Remember that the grant is due Friday", null));
  assert.ok(allowsSave("Hey Desku, don't forget I have a call at 3", null));
  assert.ok(allowsSave("Okay. Also, can you note that Sam prefers email?", null));
});

test("ordinary talk and memory questions don't", () => {
  assert.ok(!allowsSave("The grant is due Friday, just so you know.", null));
  assert.ok(!allowsSave("Do you remember what I was working on?", null));
  assert.ok(!allowsSave("", null));
});

test("yes to an offer allows saving", () => {
  assert.ok(allowsSave("Yes please", "Got it. Want me to remember that deadline?"));
  assert.ok(!allowsSave("Yes please", "Want a 25 minute timer?"));
});
