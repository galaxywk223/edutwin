# syntax=docker/dockerfile:1.7@sha256:a57df69d0ea827fb7266491f2813635de6f17269be881f696fbfdf2d83dda33e

FROM node:22-alpine AS build

WORKDIR /app

COPY frontend/package.json frontend/package-lock.json ./

RUN --mount=type=cache,target=/root/.npm \
    npm ci --no-audit --no-fund

COPY frontend/index.html frontend/tsconfig.json frontend/tsconfig.app.json frontend/tsconfig.node.json frontend/vite.config.ts ./
COPY frontend/public ./public
COPY frontend/src ./src

ARG VITE_API_BASE_URL=/api/v1
ARG VITE_EDUTWIN_DEMO_PROFILE=research
ENV VITE_API_BASE_URL=$VITE_API_BASE_URL \
    VITE_EDUTWIN_DEMO_PROFILE=$VITE_EDUTWIN_DEMO_PROFILE

RUN npm run build

FROM nginxinc/nginx-unprivileged:1.27-alpine AS runtime

USER root
RUN rm -f /etc/nginx/conf.d/default.conf
COPY frontend/nginx.conf /etc/nginx/nginx.conf
COPY --from=build --chown=101:101 /app/dist /usr/share/nginx/html

ARG APP_VERSION=development
ARG VCS_REF=unknown

LABEL org.opencontainers.image.title="EduTwin Frontend" \
      org.opencontainers.image.version="$APP_VERSION" \
      org.opencontainers.image.revision="$VCS_REF" \
      org.opencontainers.image.source="https://github.com/galaxywk223/edutwin"

USER 101:101

EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=3s --start-period=10s --retries=3 \
    CMD wget -q -O /dev/null http://127.0.0.1:8080/healthz || exit 1

ENTRYPOINT ["nginx"]
CMD ["-g", "daemon off;"]
