"""Entry point: `python -m mymts_helper` or the `mymts-helper` console script."""

from __future__ import annotations

import uvicorn

from .config import Config


def main() -> None:
    cfg = Config.from_env()
    uvicorn.run(
        "mymts_helper.app:create_app",
        factory=True,
        host="0.0.0.0",  # noqa: S104 — container binds 0.0.0.0; host network never exposes this port
        port=cfg.port,
        log_config=None,  # we own logging via mymts_helper.log
        access_log=False,
    )


if __name__ == "__main__":
    main()
