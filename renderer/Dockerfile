# MyMTS renderer — Xvfb + Chromium + PulseAudio + ffmpeg → HLS.
#
# Runs the config-driven web wall (/app/) on a virtual display and streams the
# composited video + the audible cell's audio as HLS for VLC / Apple TV.
#
# Hardening posture (mirrors the helper where Chromium allows):
#   - Base image pinned BY DIGEST (update via the registry query in helper/Dockerfile).
#   - Non-root user (uid 10002); Chromium runs with --no-sandbox in this isolated
#     container (it only ever loads OUR OWN same-origin /app/, no user input).
#   - tini as PID 1 so SIGTERM reaches the supervisor (clean ffmpeg/Chromium shutdown).
#   - No secrets baked in; all config via env. Egress is the container's normal
#     the same residential WAN path the helper uses (the helper + the public HLS the tiles play).
#
# NOT read-only-rootfs: Chromium needs writable profile/cache + Xvfb + Pulse
# runtime dirs. Writable paths are confined to tmpfs + the stream volume in compose.

ARG DEBIAN_IMAGE=debian:bookworm-slim@sha256:60eac759739651111db372c07be67863818726f754804b8707c90979bda511df
FROM ${DEBIAN_IMAGE}

ARG BUILD_SHA=dev
ARG BUILD_VERSION=0.0.0-dev
ENV BUILD_SHA=${BUILD_SHA} \
    BUILD_VERSION=${BUILD_VERSION} \
    DEBIAN_FRONTEND=noninteractive \
    PYTHONUNBUFFERED=1 \
    STREAM_DIR=/stream \
    DISPLAY=:99

# chromium (the renderer), xvfb (virtual display), pulseaudio (+ pactl via
# pulseaudio-utils, the null sink), ffmpeg (x11grab + pulse capture → HLS),
# tini (PID 1), fonts + ca-certificates (correct rendering + TLS), procps
# (the supervisor inspects processes). --no-install-recommends keeps it lean.
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        chromium \
        xvfb \
        pulseaudio pulseaudio-utils \
        ffmpeg \
        fonts-liberation fonts-dejavu-core \
        ca-certificates \
        tini procps python3 \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system --gid 10002 renderer \
    && useradd  --system --uid 10002 --gid 10002 --home /home/renderer --shell /sbin/nologin renderer \
    && mkdir -p /home/renderer /stream \
    && chown -R 10002:10002 /home/renderer /stream

WORKDIR /app
COPY supervisor.py run.py healthcheck.py ./
RUN chown -R 10002:10002 /app

USER 10002:10002
ENV HOME=/home/renderer

# Healthy iff ffmpeg is writing FRESH segments (not merely alive) — see healthcheck.py.
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
    CMD ["python3", "/app/healthcheck.py"]

ENTRYPOINT ["/usr/bin/tini", "--"]
CMD ["python3", "/app/run.py"]
