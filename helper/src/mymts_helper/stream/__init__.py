"""HLS serve route for the renderer's composited wall stream.

The renderer container (Xvfb + Chromium + ffmpeg) writes an HLS playlist +
rolling segments to a shared volume; the helper serves them at /api/stream so a
generic player (VLC on an Apple TV) opens ONE URL and watches the whole wall —
steered live by /control/. One serving surface; the helper stays the only LAN
listener. See `helper/.../stream/api.py` + `renderer/`.
"""
