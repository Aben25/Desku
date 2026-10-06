export interface Config {
  port: number;
  openaiApiKey: string;
  deviceToken: string;
  liveUrl: string;
  liveModel: string;
  voice: string;
  openaiProject: string;
  /** Saved Agents API agent to use as the brain; created from agentDefinition if empty. */
  agentId: string;
  agentDefinition: string;
  idleCloseSeconds: number;
  dataDir: string;
  timeZone: string;
  /** AgentPhone: when all three are set, Desku phones the user when they're away. */
  agentPhoneApiKey: string;
  agentPhoneNumber: string;
  ownerPhone: string;
  awayCallAfterSeconds: number;
  quietHours: [number, number] | null;
  /** Composio (the user's apps). Off when the key is empty. */
  composioApiKey: string;
  /** Keep a voice session open whenever the phone is connected (about $3/hour), instead of Talk/wake word. */
  alwaysListen: boolean;
  composioUserId: string;
}

export function loadConfig(env: NodeJS.ProcessEnv = process.env): Config {
  const missing = ["OPENAI_API_KEY", "DEVICE_TOKEN"].filter((k) => !env[k]);
  if (missing.length) {
    throw new Error(`Missing ${missing.join(", ")}. Copy .env.example to .env and fill it in.`);
  }
  return {
    port: Number(env.PORT ?? 8787),
    openaiApiKey: env.OPENAI_API_KEY!,
    deviceToken: env.DEVICE_TOKEN!,
    liveUrl: env.LIVE_URL ?? "wss://api.openai.com/v1/live/sessions",
    liveModel: env.LIVE_MODEL ?? "gpt-live-1",
    voice: env.LIVE_VOICE ?? "marin",
    openaiProject: env.OPENAI_PROJECT ?? "proj_2apst5kE7CvBS51WxiS1MWax",
    agentId: env.DESKU_AGENT_ID ?? "",
    agentDefinition: env.AGENT_DEFINITION ?? new URL("../../agent/desku-agent.json", import.meta.url).pathname,
    idleCloseSeconds: Number(env.IDLE_CLOSE_SECONDS ?? 120),
    dataDir: env.DATA_DIR ?? "./data",
    timeZone: env.TIME_ZONE ?? Intl.DateTimeFormat().resolvedOptions().timeZone,
    agentPhoneApiKey: env.AGENTPHONE_API_KEY ?? "",
    agentPhoneNumber: env.AGENTPHONE_NUMBER ?? "+16282841240",
    ownerPhone: env.OWNER_PHONE ?? "",
    awayCallAfterSeconds: Number(env.AWAY_CALL_AFTER_SECONDS ?? 60),
    quietHours: parseHours(env.QUIET_HOURS ?? "22-8"),
    composioApiKey: env.COMPOSIO_API_KEY ?? "",
    alwaysListen: (env.ALWAYS_LISTEN ?? "1") !== "0",
    composioUserId: env.COMPOSIO_USER_ID ?? "desku-owner",
  };
}

function parseHours(s: string): [number, number] | null {
  const m = /^(\d{1,2})-(\d{1,2})$/.exec(s.trim());
  return m ? [Number(m[1]), Number(m[2])] : null;
}
