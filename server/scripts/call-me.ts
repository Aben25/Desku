// Place one call now from Desku's AgentPhone number to OWNER_PHONE, then print the transcript.
// Usage: npx tsx --env-file=.env scripts/call-me.ts
import { loadConfig } from "../src/config.ts";
import { AgentPhone, mask } from "../src/phone/AgentPhone.ts";

const config = loadConfig();
if (!config.agentPhoneApiKey || !config.ownerPhone) {
  console.error("Set AGENTPHONE_API_KEY and OWNER_PHONE in server/.env first.");
  process.exit(1);
}
console.log(`Calling ${mask(config.ownerPhone)} from ${config.agentPhoneNumber}…`);
const result = await new AgentPhone(config.agentPhoneApiKey, config.agentPhoneNumber).call({
  toNumber: config.ownerPhone,
  initialGreeting:
    "Hi, it's Desku, your AI desk buddy. I've got my own phone number now, so when you're away from your desk and a check-in comes due, I'll call you here. Is now a good time for a quick hello?",
  systemPrompt:
    "You are Desku, the user's AI desk buddy, which lives in an old phone on their desk. This is a first test call to show you can phone them when they're away from the desk. Explain that in a sentence if asked, answer briefly, and keep the call under a minute. Never pretend to be human. Say goodbye and end the call when done.",
});
console.log(`Call ${result.id}: ${result.status}`);
for (const t of result.transcript) console.log(`${t.role === "user" ? "You  " : "Desku"}: ${t.content}`);
