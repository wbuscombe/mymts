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

import uvicorn

from .app import create_app
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
