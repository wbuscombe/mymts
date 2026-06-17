"""MyMTS desktop launcher — run the helper as a local web-wall.

Spike entrypoint for the PyInstaller bundle. On launch it:
  - serves the helper API + the bundled web client on http://127.0.0.1:PORT
    (HTTP, localhost only). localhost is a browser SECURE CONTEXT, so no TLS is
    needed and the http page is still allowed to load the https:// video
    streams the channels point at.
  - keeps all writable state (the seeded SQLite DB) in a USER-DATA DIR
    (~/Library/Application Support/MyMTS), never inside the frozen (read-only)
    bundle.
  - auto-opens the default browser to the wall once /health is up.

It adds NO server logic: it reuses the helper's own `create_app()` app factory
and `Config.from_env()`, and just runs uvicorn bound to localhost for a desktop
context (the packaged Docker entrypoint binds 0.0.0.0 instead). Env is set
BEFORE `Config.from_env()` reads it.
"""

from __future__ import annotations

import os
import socket
import sys
import threading
import time
import urllib.request
import webbrowser
from pathlib import Path

import uvicorn

from mymts_helper.app import create_app
from mymts_helper.config import Config

APP_NAME = "MyMTS"
DEFAULT_PORT = 8091


def _user_data_dir() -> Path:
    """Writable per-user state dir (macOS). Frozen bundles are read-only, so the
    DB and any state MUST live here — not next to the executable."""
    base = Path.home() / "Library" / "Application Support" / APP_NAME
    base.mkdir(parents=True, exist_ok=True)
    return base


def _bundled_web_dir() -> Path | None:
    """Locate the web client. Frozen: under the PyInstaller bundle (_MEIPASS).
    Dev run (this file under tools/desktop/): the repo's web/ at the root."""
    if getattr(sys, "frozen", False):
        meipass = Path(getattr(sys, "_MEIPASS", Path(sys.executable).parent))
        cand = meipass / "web"
        return cand if cand.is_dir() else None
    repo_web = Path(__file__).resolve().parents[2] / "web"
    return repo_web if repo_web.is_dir() else None


def _resolve_port(preferred: int) -> int:
    """Use the preferred port if free, else an ephemeral one (so a second
    instance / a port clash doesn't crash the launch)."""
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        try:
            s.bind(("127.0.0.1", preferred))
            return preferred
        except OSError:
            s.bind(("127.0.0.1", 0))
            return s.getsockname()[1]


def _open_when_ready(wall_url: str, health_url: str, timeout_s: float = 40.0) -> None:
    """Poll /health, then open the browser to the wall."""
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        try:
            with urllib.request.urlopen(health_url, timeout=1.5) as r:
                if r.status == 200:
                    webbrowser.open(wall_url)
                    return
        except Exception:  # noqa: BLE001 — server not up yet; keep polling
            pass
        time.sleep(0.4)
    webbrowser.open(wall_url)  # last resort: open anyway so the user sees something


def main() -> None:
    port = _resolve_port(int(os.environ.get("MYMTS_DESKTOP_PORT", str(DEFAULT_PORT))))
    data_dir = _user_data_dir()
    web_dir = _bundled_web_dir()

    # Drive the existing helper via its env-driven Config: HTTP on localhost,
    # real (non-phantom) wall, DB in the user-data dir, serve the bundled web.
    os.environ["DATA_DIR"] = str(data_dir)
    os.environ["PORT"] = str(port)
    os.environ.setdefault("PHANTOM_MODE", "0")
    os.environ.setdefault("LOG_LEVEL", "info")
    if web_dir is not None:
        os.environ["WEB_CLIENT_DIR"] = str(web_dir)

    cfg = Config.from_env()
    app = create_app(cfg)

    wall_url = f"http://127.0.0.1:{port}/app/"
    health_url = f"http://127.0.0.1:{port}/health"
    print(f"[MyMTS] data dir  : {data_dir}", flush=True)
    print(f"[MyMTS] web client: {web_dir}", flush=True)
    print(f"[MyMTS] wall       : {wall_url}", flush=True)
    print("[MyMTS] Ctrl-C to quit.", flush=True)

    threading.Thread(
        target=_open_when_ready, args=(wall_url, health_url), daemon=True
    ).start()

    # Reuse the helper app; bind localhost over HTTP (no TLS). Force the asyncio
    # loop + h11 so the frozen bundle doesn't depend on uvloop/httptools (which
    # are fiddly to freeze); uvicorn falls back to these cleanly anyway.
    uvicorn.run(
        app,
        host="127.0.0.1",
        port=port,
        loop="asyncio",
        http="h11",
        log_config=None,
        access_log=False,
    )


if __name__ == "__main__":
    main()
