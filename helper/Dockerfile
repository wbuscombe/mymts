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

# Base image pinned BY DIGEST (Stage 2). Update via:
#   curl -sI -H "Accept: application/vnd.oci.image.index.v1+json" \
#     "https://registry-1.docker.io/v2/library/python/manifests/<tag>" \
#     -H "Authorization: Bearer $(curl -s 'https://auth.docker.io/token?service=registry.docker.io&scope=repository:library/python:pull' | jq -r .token)" \
#   | grep -i docker-content-digest
# The tag in the FROM line stays for human readability; the digest is the
# source of truth.
ARG PYTHON_IMAGE=python:3.13.1-slim-bookworm@sha256:031ebf3cde9f3719d2db385233bcb18df5162038e9cda20e64e08f49f4b47a2f

# ---- builder ----
FROM ${PYTHON_IMAGE} AS builder

ENV PIP_DISABLE_PIP_VERSION_CHECK=1 \
    PIP_NO_CACHE_DIR=1 \
    PYTHONDONTWRITEBYTECODE=1

WORKDIR /build
COPY pyproject.toml ./
COPY src ./src
RUN pip install --no-cache-dir build hatchling \
    && python -m build --wheel --outdir /wheels \
    && pip wheel --wheel-dir /wheels --no-deps . \
    && pip wheel --wheel-dir /wheels \
        fastapi==0.118.0 \
        uvicorn[standard]==0.37.0 \
        httpx==0.28.1 \
        feedparser==6.0.11 \
        defusedxml==0.7.1 \
        yt-dlp==2026.3.17

# ---- runtime ----
FROM ${PYTHON_IMAGE} AS runtime

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
    && rm -rf /wheels \
    && mkdir -p /data \
    && chown -R 10001:10001 /data /app

# Named volumes mounted on /data inherit the owner of /data inside the
# image when the volume is fresh (Docker's "initialize-from-image" rule),
# so this chown is what lets the non-root container write to /data on
# first start without a host-side privileged step.
USER 10001:10001

EXPOSE 8091

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD curl -fsS http://127.0.0.1:8091/health >/dev/null || exit 1

ENTRYPOINT ["/usr/bin/tini", "--"]
CMD ["python", "-m", "mymts_helper"]
