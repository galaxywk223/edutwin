# syntax=docker/dockerfile:1.7@sha256:a57df69d0ea827fb7266491f2813635de6f17269be881f696fbfdf2d83dda33e

FROM python:3.11-slim-bookworm@sha256:f5cf0344c9886ff24d34797578d5d7dd6e8911ae0fe5962bb55d0f89603ec361 AS build

ENV VIRTUAL_ENV=/opt/venv \
    PATH=/opt/venv/bin:$PATH \
    PIP_DISABLE_PIP_VERSION_CHECK=1

RUN python -m venv "$VIRTUAL_ENV"

WORKDIR /build/modeling

COPY modeling/pyproject.toml ./

RUN --mount=type=cache,target=/root/.cache/pip \
    python - <<'PY'
import pathlib
import tomllib

project = tomllib.loads(pathlib.Path("pyproject.toml").read_text(encoding="utf-8"))
dependencies = project["project"]["dependencies"]
pathlib.Path("/tmp/runtime-requirements.txt").write_text(
    "\n".join(dependencies) + "\n", encoding="utf-8"
)
PY

RUN --mount=type=cache,target=/root/.cache/pip \
    pip install --upgrade "pip==25.1.1" && \
    pip install --index-url https://download.pytorch.org/whl/cpu "torch==2.7.1" && \
    pip install --requirement /tmp/runtime-requirements.txt

FROM python:3.11-slim-bookworm@sha256:f5cf0344c9886ff24d34797578d5d7dd6e8911ae0fe5962bb55d0f89603ec361 AS runtime

RUN apt-get update && \
    apt-get install -y --no-install-recommends curl libgomp1 && \
    rm -rf /var/lib/apt/lists/* && \
    groupadd --gid 10001 edutwin && \
    useradd --uid 10001 --gid 10001 --no-create-home --shell /usr/sbin/nologin edutwin

COPY --link --from=build /opt/venv /opt/venv

WORKDIR /app

# Frozen manifests use repository-relative paths. The runtime image preserves the
# repository's modeling/ boundary without including raw or processed datasets.
COPY --link --chown=10001:10001 modeling/src/ /app/modeling/src/
COPY --link --chown=10001:10001 modeling/pyproject.toml /app/modeling/pyproject.toml
COPY --link --chown=10001:10001 modeling/configs/serving/ /app/modeling/configs/serving/
COPY --link --chown=10001:10001 modeling/artifacts/knowledge/ /app/modeling/artifacts/knowledge/
COPY --link --chown=10001:10001 modeling/artifacts/risk/ /app/modeling/artifacts/risk/
COPY --link --chown=10001:10001 modeling/artifacts/manifests/ /app/modeling/artifacts/manifests/
COPY --link --chown=10001:10001 data/demo/generated/manifest.json /app/demo/manifest.json
COPY --link --chown=10001:10001 data/demo/generated/database/knowledge_lineage.csv /app/demo/database/knowledge_lineage.csv

ENV PATH=/opt/venv/bin:$PATH \
    PYTHONPATH=/app/modeling/src \
    PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    OMP_NUM_THREADS=2 \
    MKL_NUM_THREADS=2 \
    CUDA_VISIBLE_DEVICES="" \
    EDUTWIN_MODEL_ARTIFACT_ROOT=/app/modeling/artifacts \
    EDUTWIN_MODEL_CONFIG=/app/modeling/configs/serving/service.yaml \
    EDUTWIN_LINEAGE_MANIFEST=/app/demo/manifest.json \
    TZ=UTC

ARG APP_VERSION=development
ARG VCS_REF=unknown

LABEL org.opencontainers.image.title="EduTwin Model Service" \
      org.opencontainers.image.version="$APP_VERSION" \
      org.opencontainers.image.revision="$VCS_REF" \
      org.opencontainers.image.source="https://github.com/galaxywk223/edutwin"

USER 10001:10001

EXPOSE 8000

HEALTHCHECK --interval=15s --timeout=3s --start-period=60s --retries=5 \
    CMD curl --fail --silent --show-error http://127.0.0.1:8000/health >/dev/null || exit 1

CMD ["uvicorn", "edutwin_modeling.serving.app:app", "--host", "0.0.0.0", "--port", "8000", "--workers", "1", "--proxy-headers", "--forwarded-allow-ips=*"]
