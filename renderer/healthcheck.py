#!/usr/bin/env python3
"""Docker HEALTHCHECK for the renderer: exit 0 iff the HLS stream is fresh.

A renderer whose ffmpeg has wedged (Chromium black-screened, X grab stalled,
disk full) still has a live PID but a STALE stream — useless to VLC. This checks
the actual output (a segment written within the freshness window), so docker
marks the container unhealthy and the supervisor/restart policy can act, instead
of a liveness-only check that would call a frozen stream 'up'.
"""

from __future__ import annotations

import os
import sys
import time

import supervisor

STREAM_DIR = os.environ.get("STREAM_DIR", "/stream")
MAX_AGE = float(os.environ.get("RENDER_MAX_SEGMENT_AGE_S", str(supervisor.DEFAULT_MAX_SEGMENT_AGE_S)))

if supervisor.is_stream_healthy(STREAM_DIR, time.time(), MAX_AGE):
    sys.exit(0)

age = supervisor.stream_age_seconds(STREAM_DIR, time.time())
print(f"stream unhealthy: newest segment age={age} (max {MAX_AGE}s)", file=sys.stderr)
sys.exit(1)
