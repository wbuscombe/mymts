#!/usr/bin/env python3
"""Mercury publisher — dev-SFU verification harness (NOT shipped in the image).

Proves the WHOLE publisher pipeline against a THROWAWAY local LiveKit dev SFU, with no
Mercury dependency: starts a minimal Xvfb (the publisher Chrome needs a display) and
runs the real RealMercuryPublisher pointed at the dev SFU. The publisher plays the
LIVE wall HLS (RENDER_HLS_URL → the helper's stream — render-once, the real wall) and
publishes it as a simulcast screen_share track. Watch status() converge to publishing;
an external `lk` check then confirms the track + simulcast layers + the publish-only
grant.

    docker run -i --rm --network mymts-net --shm-size=512m \
      -e LIVEKIT_API_KEY=devkey -e LIVEKIT_API_SECRET=secret \
      -e LIVEKIT_HOST=ws://mymts-lk-dev:7880 \
      -e RENDER_HLS_URL=http://mymts-helper:8082/api/stream/playlist.m3u8 \
      -v <repo>/renderer/tools/mercury_devsfu_harness.py:/app/harness.py:ro \
      --name mymts-mercury-harness mymts-renderer:mercury-devtest python3 /app/harness.py

See docs/decisions/0003 for the full recipe (dev SFU up, lk verify, teardown).
"""

from __future__ import annotations

import json
import os
import subprocess
import sys
import time

sys.path.insert(0, "/app")
import mercury  # noqa: E402  (the renderer image puts mercury.py at /app)

DISPLAY = ":99"
W, H, FPS = 1920, 1080, 30
GUID = os.environ.get("MERCURY_TEST_GUID", "devtest")


def log(m: str) -> None:
    print(f"[harness] {m}", flush=True)


def main() -> int:
    # A display for the (off-screen) publisher Chrome — captureStream needs Chrome
    # running, not a visible framebuffer.
    subprocess.Popen(
        ["Xvfb", DISPLAY, "-screen", "0", f"{W}x{H}x24", "-nolisten", "tcp"],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
    )
    time.sleep(2)
    os.environ["DISPLAY"] = DISPLAY
    log(f"Xvfb up; HLS source = {os.environ.get('RENDER_HLS_URL', mercury.DEFAULT_HLS_URL)}")

    pub = mercury.make_publisher(env=os.environ, log=log, display=DISPLAY, chromium_bin="chromium")
    log(f"publisher type: {type(pub).__name__}")
    pub.set_canvas(W, H, FPS)
    pub.configure({
        "enabled": True, "channel_guid": GUID, "resolution": "1080p",
        "bitrate_kbps": 8000, "audio": False, "display_name": "MyMTS News Wall",
    })
    pub.start()

    deadline = time.time() + 600
    while time.time() < deadline:
        time.sleep(3)
        log("status " + json.dumps(pub.status()))
    return 0


if __name__ == "__main__":
    sys.exit(main())
