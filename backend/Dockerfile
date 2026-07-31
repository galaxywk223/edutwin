# syntax=docker/dockerfile:1.7@sha256:a57df69d0ea827fb7266491f2813635de6f17269be881f696fbfdf2d83dda33e

FROM maven:3.9.9-eclipse-temurin-21 AS build

WORKDIR /workspace

COPY backend/pom.xml backend/pom.xml
COPY contracts/openapi/public-api.yaml contracts/openapi/public-api.yaml

RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -f backend/pom.xml dependency:go-offline

COPY backend/src backend/src

RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -f backend/pom.xml -DskipTests package && \
    jar_file="$(find backend/target -maxdepth 1 -type f -name '*.jar' \
      ! -name '*.jar.original' ! -name '*-sources.jar' ! -name '*-javadoc.jar' \
      | head -n 1)" && \
    test -n "$jar_file" && \
    cp "$jar_file" /workspace/app.jar

FROM eclipse-temurin:21-jre-jammy AS runtime

RUN apt-get update && \
    apt-get install -y --no-install-recommends curl && \
    rm -rf /var/lib/apt/lists/* && \
    groupadd --gid 10001 edutwin && \
    useradd --uid 10001 --gid 10001 --no-create-home --shell /usr/sbin/nologin edutwin

WORKDIR /app

COPY --link --from=build --chown=10001:10001 /workspace/app.jar /app/app.jar
COPY --link --chown=10001:10001 data/demo/generated/manifest.json /app/demo/manifest.json
COPY --link --chown=10001:10001 data/demo/generated/database/ /app/demo/database/

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70.0 -XX:InitialRAMPercentage=20.0 -XX:+ExitOnOutOfMemoryError -Dfile.encoding=UTF-8" \
    TZ=UTC

ARG APP_VERSION=development
ARG VCS_REF=unknown

LABEL org.opencontainers.image.title="EduTwin Backend" \
      org.opencontainers.image.version="$APP_VERSION" \
      org.opencontainers.image.revision="$VCS_REF" \
      org.opencontainers.image.source="https://github.com/galaxywk223/edutwin"

USER 10001:10001

EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=3s --start-period=40s --retries=5 \
    CMD curl --fail --silent --show-error http://127.0.0.1:8080/actuator/health/readiness >/dev/null || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
