FROM node:22-alpine AS seed

WORKDIR /workspace
COPY frontend/scripts/generate-showcase.mjs frontend/scripts/generate-showcase.mjs
RUN node frontend/scripts/generate-showcase.mjs

FROM node:22-alpine

WORKDIR /app
COPY infra/showcase-api/server.mjs /app/server.mjs
COPY --from=seed /workspace/frontend/public/showcase-data.json /app/showcase-data.json

ENV NODE_ENV=production \
    PORT=8080 \
    EDUTWIN_DEMO_PROFILE=showcase \
    EDUTWIN_MODEL_ENABLED=false

USER node
EXPOSE 8080
HEALTHCHECK --interval=5s --timeout=2s --start-period=3s --retries=10 \
  CMD wget -q -O /dev/null http://127.0.0.1:8080/healthz || exit 1

CMD ["node", "/app/server.mjs"]
