"""Entry point: `python -m mymts_helper` or the `mymts-helper` console script.

Stage 6 / TLS track — the entry point now optionally serves **both** HTTP
and HTTPS from the same FastAPI app instance. The behaviour:

  - If only `PORT` is set → HTTP only (the v1 behaviour, unchanged).
  - If `HTTPS_PORT` + `SSL_KEYFILE` + `SSL_CERTFILE` are all set in
    addition to `PORT` → BOTH listeners come up, sharing one app
    instance. Background pollers run **once** via the app's lifespan
    on the primary (HTTP) listener; the HTTPS listener serves the
    same app surface with lifespan disabled so the pollers do not
    double-start.
  - If only HTTPS is configured (no `PORT`) → HTTPS only.

The dual-port mode is the operator-away-safe transition state: the
existing HTTP endpoint stays available while the TV moves to HTTPS,
and the HTTP endpoint can be removed in a follow-up commit once the
move is telemetry-confirmed.
"""

from __future__ import annotations

import asyncio
import logging
from pathlib import Path

import uvicorn

from .app import create_app, create_public_app, create_stream_app
from .config import Config

log = logging.getLogger("mymts_helper.entry")


async def _serve(cfg: Config) -> None:
    # Build the app ONCE. Both servers (HTTP + HTTPS) share this
    # single instance, so the background pollers in the lifespan run
    # exactly once even when two listeners are active.
    app = create_app(cfg)

    servers: list[uvicorn.Server] = []

    if cfg.port:
        http_cfg = uvicorn.Config(
            app=app,
            host="0.0.0.0",  # noqa: S104 — Docker NAT terminates here; host port is LAN-only
            port=cfg.port,
            log_config=None,
            access_log=False,
            lifespan="on",
        )
        servers.append(uvicorn.Server(http_cfg))
        log.info("listener_http_configured", extra={"port": cfg.port})

    if cfg.has_https():
        https_cfg = uvicorn.Config(
            app=app,
            host="0.0.0.0",  # noqa: S104
            port=cfg.https_port,
            ssl_keyfile=cfg.ssl_keyfile,
            ssl_certfile=cfg.ssl_certfile,
            log_config=None,
            access_log=False,
            # Lifespan ran on the HTTP listener; running it again here
            # would double-start the pollers. With "off", uvicorn skips
            # lifespan dispatch entirely; the app is already initialised.
            # If HTTPS is the only listener configured, change this to
            # "on" below.
            lifespan="off" if cfg.port else "on",
        )
        servers.append(uvicorn.Server(https_cfg))
        log.info(
            "listener_https_configured",
            extra={"port": cfg.https_port, "cert": cfg.ssl_certfile},
        )

    # Plain-HTTP, stream-ONLY listener (2026-06-25). A strict tvOS client (VLC on
    # Apple TV) hangs forever on the self-signed HTTPS; a LAN video stream needs
    # no TLS. This serves a SEPARATE minimal app exposing only the hardened
    # /api/stream route — the API / /control/ / /app/ stay HTTPS-only on the main
    # app above. No lifespan (no pollers; it only reads the shared stream volume).
    if cfg.stream_http_port and cfg.stream_dir:
        stream_app = create_stream_app(cfg.stream_dir)
        stream_cfg = uvicorn.Config(
            app=stream_app,
            host="0.0.0.0",  # noqa: S104 — Docker NAT terminates here; host port is LAN-only
            port=cfg.stream_http_port,
            log_config=None,
            access_log=False,
            lifespan="off",
        )
        servers.append(uvicorn.Server(stream_cfg))
        log.info("listener_stream_http_configured", extra={"port": cfg.stream_http_port})

    # Dedicated PUBLIC listener for the Discord Activity (2026-06-28). A SEPARATE
    # minimal app (Activity static + /api/discord/* + the /api/stream passthrough)
    # — the full API / /control/ / /app/ are NOT on it. Plain HTTP; the operator's
    # Cloudflare tunnel terminates TLS and maps the public hostname to this port.
    # Opt-in via DISCORD_PUBLIC_PORT + DISCORD_ACTIVITY_DIR + STREAM_DIR (see
    # Config.has_discord_public); unset → no public surface. No lifespan (no pollers).
    if cfg.has_discord_public() and Path(cfg.discord_activity_dir).is_dir():
        public_app = create_public_app(
            stream_dir=cfg.stream_dir,
            activity_dir=cfg.discord_activity_dir,
            discord_client_id=cfg.discord_client_id,
            discord_client_secret=cfg.discord_client_secret,
        )
        public_cfg = uvicorn.Config(
            app=public_app,
            host="0.0.0.0",  # noqa: S104 — the CF tunnel fronts this; the host port is not LAN-trusted
            port=cfg.discord_public_port,
            log_config=None,
            access_log=False,
            lifespan="off",
        )
        servers.append(uvicorn.Server(public_cfg))
        log.info("listener_discord_public_configured", extra={"port": cfg.discord_public_port})
    elif cfg.has_discord_public():
        # Configured to start but the Activity static dir is missing (bad mount) —
        # warn + skip rather than crash the whole helper.
        log.warning("discord_public_skipped_missing_dir", extra={"dir": cfg.discord_activity_dir})

    if not servers:
        raise RuntimeError(
            "no listeners configured — set PORT and/or HTTPS_PORT+SSL_KEYFILE+SSL_CERTFILE"
        )

    # gather() runs every server concurrently. When one stops (e.g.
    # SIGTERM in the container), the others are cancelled too.
    await asyncio.gather(*[s.serve() for s in servers])


def main() -> None:
    cfg = Config.from_env()
    asyncio.run(_serve(cfg))


if __name__ == "__main__":
    main()
