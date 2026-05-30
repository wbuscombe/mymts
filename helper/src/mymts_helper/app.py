"""FastAPI application factory.

Stage 1 exposes /health only — the helper does no aggregation and no stream
resolution yet (those land in Stage 2). The factory pattern lets tests
spin up an isolated app instance per test without shared state leaks.
"""

from __future__ import annotations

import logging
import time

from fastapi import FastAPI

from .config import Config
from .health import HealthState
from .log import configure_logging

log = logging.getLogger("mymts_helper")


def create_app(config: Config | None = None) -> FastAPI:
    cfg = config or Config.from_env()
    configure_logging(cfg.log_level)

    app = FastAPI(
        title="MyMTS Helper",
        version=cfg.build_version,
        # No docs/redoc by default — the helper has no public HTTP surface and
        # the schema is contract-tested, not browsed.
        docs_url=None,
        redoc_url=None,
        openapi_url=None,
    )
    health_state = HealthState(started_at=time.monotonic())

    @app.get("/health")
    def health() -> dict[str, object]:
        return health_state.snapshot(cfg)

    log.info(
        "helper_started",
        extra={"port": cfg.port, "phantom": cfg.phantom_mode, "version": cfg.build_version},
    )
    return app
