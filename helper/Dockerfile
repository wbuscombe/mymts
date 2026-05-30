# MyMTS helper — minimal NAS-side service.
#
# Hardening posture (Trust Bar A2 / A4 / A7 / Op Bar B1):
#   - Pinned base image by tag (digest pin lands in Stage 6 hardening).
#   - Non-root user.
#   - tini as PID 1 so kernel signals reach uvicorn cleanly.
#   - No build tools in the runtime layer.
#   - No secrets baked in — BUILD_SHA / BUILD_VERSION are the only build args,
#     and they are not secrets.
#   - HEALTHCHECK uses /health so a stuck process is detectable.

# ---- builder ----
FROM python:3.13.1-slim-bookworm AS builder

ENV PIP_DISABLE_PIP_VERSION_CHECK=1 \
    PIP_NO_CACHE_DIR=1 \
    PYTHONDONTWRITEBYTECODE=1

WORKDIR /build
COPY pyproject.toml ./
COPY src ./src
RUN pip install --no-cache-dir build hatchling \
    && python -m build --wheel --outdir /wheels \
    && pip wheel --wheel-dir /wheels --no-deps . \
    && pip download --dest /wheels --no-deps \
        fastapi==0.118.0 uvicorn[standard]==0.37.0

# ---- runtime ----
FROM python:3.13.1-slim-bookworm AS runtime

ARG BUILD_SHA=dev
ARG BUILD_VERSION=0.0.0-dev

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1 \
    PIP_NO_CACHE_DIR=1 \
    BUILD_SHA=${BUILD_SHA} \
    BUILD_VERSION=${BUILD_VERSION}

RUN apt-get update \
    && apt-get install -y --no-install-recommends tini curl ca-certificates \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system --gid 10001 mymts \
    && useradd  --system --uid 10001 --gid 10001 --home /app --shell /sbin/nologin mymts

WORKDIR /app
COPY --from=builder /wheels /wheels
RUN pip install --no-cache-dir --no-index --find-links=/wheels mymts-helper \
    && rm -rf /wheels

USER 10001:10001

EXPOSE 8091

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD curl -fsS http://127.0.0.1:8091/health >/dev/null || exit 1

ENTRYPOINT ["/usr/bin/tini", "--"]
CMD ["python", "-m", "mymts_helper"]
