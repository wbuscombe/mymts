"""MyMTS desktop launcher — run the helper as a local web-wall.

Entrypoint for the PyInstaller bundle. It serves the helper API + the bundled
web client on http://127.0.0.1:PORT (HTTP, localhost only — a browser SECURE
CONTEXT, so no TLS is needed and the http page may still load the https video
streams), keeping all writable state (the seeded DB) in a user-data dir.

Two launch modes:
  - TRAY (macOS / Windows desktop): the helper runs in a background thread; a
    menu-bar / system-tray icon offers "Open MyMTS" and "Quit". The browser
    auto-opens once on launch. Built with PyInstaller --windowed (no console).
  - HEADLESS (Linux servers / no display / no tray backend / MYMTS_HEADLESS=1):
    the helper runs in the foreground, prints the reachable URL, no tray, no
    auto-browser. Degrades gracefully — a missing tray never crashes the app.

It adds NO server logic: it reuses the helper's own `create_app()` /
`Config.from_env()` and binds uvicorn to localhost.
"""

from __future__ import annotations

import asyncio
import os
import socket
import sys
import threading
import time
import urllib.request
import webbrowser
from pathlib import Path

import uvicorn

APP_NAME = "MyMTS"
DEFAULT_PORT = 8091


# ── paths / env ─────────────────────────────────────────────────────────────

def user_data_dir() -> Path:
    """Writable per-user state dir. Frozen bundles are read-only, so the DB and
    any cached state MUST live here, not next to the executable.

      macOS:   ~/Library/Application Support/MyMTS
      Windows: %LOCALAPPDATA%/MyMTS
      Linux:   $XDG_DATA_HOME/MyMTS or ~/.local/share/MyMTS
    """
    if sys.platform == "darwin":
        base = Path.home() / "Library" / "Application Support" / APP_NAME
    elif sys.platform == "win32":
        root = os.environ.get("LOCALAPPDATA") or str(Path.home() / "AppData" / "Local")
        base = Path(root) / APP_NAME
    else:
        root = os.environ.get("XDG_DATA_HOME") or str(Path.home() / ".local" / "share")
        base = Path(root) / APP_NAME
    base.mkdir(parents=True, exist_ok=True)
    return base


def bundled_web_dir() -> Path | None:
    """Locate the web client. Frozen: under the PyInstaller bundle (_MEIPASS).
    Dev run (this file under tools/desktop/): the repo's web/ at the root."""
    if getattr(sys, "frozen", False):
        meipass = Path(getattr(sys, "_MEIPASS", Path(sys.executable).parent))
        cand = meipass / "web"
        return cand if cand.is_dir() else None
    repo_web = Path(__file__).resolve().parents[2] / "web"
    return repo_web if repo_web.is_dir() else None


def resolve_port(preferred: int) -> int:
    """The preferred port if free, else an ephemeral one (a second instance /
    a port clash must not crash the launch)."""
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        try:
            s.bind(("127.0.0.1", preferred))
            return preferred
        except OSError:
            s.bind(("127.0.0.1", 0))
            return s.getsockname()[1]


# ── tray-vs-headless decision (pure, testable) ──────────────────────────────

def should_use_tray(
    platform: str,
    *,
    forced_headless: bool,
    has_display: bool,
    pystray_importable: bool,
) -> bool:
    """Decide tray vs headless from explicit inputs (pure — unit-tested).

    - MYMTS_HEADLESS=1 always wins -> headless.
    - macOS / Windows are desktop platforms -> tray iff pystray imports.
    - Linux (and anything else) needs BOTH a display AND pystray -> else
      headless (the common server case).
    """
    if forced_headless:
        return False
    if not pystray_importable:
        return False
    if platform in ("darwin", "win32"):
        return True
    return has_display


def _tray_available() -> bool:
    """Gather the real inputs for [should_use_tray]."""
    forced = os.environ.get("MYMTS_HEADLESS", "") == "1"
    has_display = bool(os.environ.get("DISPLAY") or os.environ.get("WAYLAND_DISPLAY"))
    try:
        import pystray  # noqa: F401
        from PIL import Image  # noqa: F401
        importable = True
    except Exception:  # noqa: BLE001 — a missing tray backend must not crash
        importable = False
    return should_use_tray(
        sys.platform,
        forced_headless=forced,
        has_display=has_display,
        pystray_importable=importable,
    )


# ── helper server in a background thread ────────────────────────────────────

class HelperServer:
    """Runs the helper's uvicorn server off the main thread so the tray event
    loop can own the main thread (required by the macOS menu-bar backend).
    `serve()` (not `run()`) is used so uvicorn doesn't install signal handlers
    on a non-main thread."""

    def __init__(self, app, host: str, port: int) -> None:
        config = uvicorn.Config(
            app, host=host, port=port, loop="asyncio", http="h11",
            log_config=None, access_log=False,
        )
        self.server = uvicorn.Server(config)
        self._thread: threading.Thread | None = None

    def start(self) -> None:
        self._thread = threading.Thread(target=self._run, name="mymts-helper", daemon=True)
        self._thread.start()

    def _run(self) -> None:
        asyncio.run(self.server.serve())

    def stop(self) -> None:
        self.server.should_exit = True  # uvicorn polls this and shuts down cleanly
        if self._thread is not None:
            self._thread.join(timeout=6)

    def join(self) -> None:
        if self._thread is not None:
            self._thread.join()


def _open_when_ready(wall_url: str, health_url: str, timeout_s: float = 40.0) -> None:
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        try:
            # health_url is our own hardcoded http://127.0.0.1 endpoint (not user input).
            with urllib.request.urlopen(health_url, timeout=1.5) as r:  # noqa: S310
                if r.status == 200:
                    webbrowser.open(wall_url)
                    return
        except Exception:  # noqa: BLE001 — server not up yet
            pass
        time.sleep(0.4)
    webbrowser.open(wall_url)


def _make_icon():
    """A simple branded menu-bar icon (no asset file): a rounded square in the
    MyMTS green with a white 'M'. Pillow only."""
    from PIL import Image, ImageDraw

    size = 64
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle([4, 4, size - 4, size - 4], radius=12, fill=(0, 229, 160, 255))
    # a blocky 'M' so it reads at menu-bar size
    d.line([(16, 46), (16, 18), (32, 38), (48, 18), (48, 46)], fill=(5, 5, 5, 255), width=6)
    return img


def _run_tray(srv: HelperServer, wall_url: str) -> None:
    import pystray

    def on_open(icon, item):  # noqa: ARG001
        webbrowser.open(wall_url)

    def on_quit(icon, item):  # noqa: ARG001
        srv.stop()
        icon.stop()

    menu = pystray.Menu(
        pystray.MenuItem("Open MyMTS", on_open, default=True),
        pystray.MenuItem("Quit", on_quit),
    )
    icon = pystray.Icon(APP_NAME, _make_icon(), APP_NAME, menu)
    icon.run()  # blocks the MAIN thread on the platform tray event loop


def _configure_env(port: int) -> Path | None:
    data_dir = user_data_dir()
    web_dir = bundled_web_dir()
    os.environ["DATA_DIR"] = str(data_dir)
    os.environ["PORT"] = str(port)
    os.environ.setdefault("PHANTOM_MODE", "0")
    os.environ.setdefault("LOG_LEVEL", "info")
    if web_dir is not None:
        os.environ["WEB_CLIENT_DIR"] = str(web_dir)
    return web_dir


def main() -> None:
    port = resolve_port(int(os.environ.get("MYMTS_DESKTOP_PORT", str(DEFAULT_PORT))))
    web_dir = _configure_env(port)

    # Import AFTER env is set (Config.from_env reads it at construction).
    from mymts_helper.app import create_app
    from mymts_helper.config import Config

    app = create_app(Config.from_env())
    srv = HelperServer(app, "127.0.0.1", port)
    srv.start()

    wall_url = f"http://127.0.0.1:{port}/app/"
    health_url = f"http://127.0.0.1:{port}/health"
    print(f"[MyMTS] data dir  : {user_data_dir()}", flush=True)
    print(f"[MyMTS] web client: {web_dir}", flush=True)
    print(f"[MyMTS] wall       : {wall_url}", flush=True)

    if _tray_available():
        # Desktop: open the browser once, then own the main thread with the tray.
        threading.Thread(
            target=_open_when_ready, args=(wall_url, health_url), daemon=True
        ).start()
        print("[MyMTS] tray mode — use the menu-bar / tray icon to Open or Quit.", flush=True)
        _run_tray(srv, wall_url)
    else:
        # Headless: foreground server, no tray, no auto-browser.
        print(f"[MyMTS] headless mode — open {wall_url} in a browser. Ctrl-C to quit.", flush=True)
        try:
            srv.join()
        except KeyboardInterrupt:
            srv.stop()


if __name__ == "__main__":
    main()
