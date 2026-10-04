# The Desku engine (server/) for Fly.io. Build from the repo root: it also needs the agent
# definition in agent/. Secrets come from `fly secrets`, never from an .env in the image.
FROM node:22-slim

WORKDIR /app/server
COPY server/package.json server/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY server/tsconfig.json ./
COPY server/src ./src
COPY server/public ./public
COPY agent/desku-agent.json /app/agent/desku-agent.json

ENV NODE_ENV=production \
    PORT=8787 \
    DATA_DIR=/data \
    AGENT_DEFINITION=/app/agent/desku-agent.json
EXPOSE 8787
CMD ["npx", "tsx", "src/index.ts"]
