/**
 * Enforced in code, not only in the prompt: a memory is saved only when the user's own latest
 * words ask for it ("remember that...", "don't forget...", "make a note..."), or when they say
 * yes right after Desku offered to remember something. Questions about memory ("do you
 * remember...?") and ordinary chat never qualify, so ambient conversation is never stored.
 * Ported from the Android app's MemoryPolicy.kt.
 */
const questionsAboutMemory = new RegExp(
  "\\b(do|did|can|could) you (still )?remember (what|when|where|who|how|if|whether|anything)\\b" +
    "|\\bdo you remember\\b|\\bdid you remember\\b|\\bwhat do you remember\\b" +
    "|\\bremember when\\b|\\b(i|you) (don'?t|do not|can'?t|cannot) remember\\b" +
    "|\\bwhat did i (ask|tell) you to remember\\b",
);

const saveRequests = [
  new RegExp(
    "^((hey )?(buddy|desku)[,.!]?\\s*)?((please|ok|okay|and|also|oh|so|alright|right)[,]?\\s+)*" +
      "(remember|note|save|don'?t forget|do not forget|keep in mind|make a note|write (this|that|it) down|jot (this|that|it) down)\\b",
  ),
  /\b(can|could|would|will) you (please )?(remember|note|save|keep in mind|make a note|write (this|that|it) down)\b/,
  /\b(i want|i'd like|i would like|i need) you to (remember|note|save|keep in mind)\b/,
  /\b(please )?remember (that|this|to|my|i|i'm|me|it)\b/,
  /\b(save|store) (this|that|it)( for me| to (your )?memory)?\b/,
  /\badd (this|that|it) to (your )?(memory|memories|notes)\b/,
  /\bdon'?t let me forget\b|\bmake a note\b|\bnote (that|this|down)\b/,
];

const affirmations =
  /^(yes|yeah|yep|yup|sure|please|ok|okay|do it|go ahead|sounds good|please do|absolutely|definitely)\b/;

const offerToRemember = /\b(remember|save|note|keep)\b[^?]*\?/;

const normalize = (s: string) => s.toLowerCase().replace(/’/g, "'").replace(/\s+/g, " ").trim();

export function allowsSave(latestUserWords: string, previousAssistantWords: string | null): boolean {
  const said = normalize(latestUserWords);
  if (!said) return false;
  // Speech since the last delegation can span several sentences; check each one.
  const sentences = said.split(/(?<=[.!?])\s+/);
  const explicit = sentences.some(
    (s) => saveRequests.some((r) => r.test(s)) && !questionsAboutMemory.test(s),
  );
  if (explicit) return true;
  const offered = previousAssistantWords ? offerToRemember.test(normalize(previousAssistantWords)) : false;
  return offered && affirmations.test(said);
}
