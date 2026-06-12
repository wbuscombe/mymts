#!/usr/bin/env python3
"""Stage 6 update path — post-install health check.

Given a window of `MYMTS_SOAK` logcat output produced after installing a new
build, decide whether the install is healthy enough to promote — or should
be rolled back.

The decision is **purely a function of the telemetry blob** so it's
unit-testable without a device. The deploy script (`scripts/deploy-app.sh`)
calls this with a real logcat capture; tests pass canned strings.

Operational Bar mapping:
  - **B5 (no silent bad-bundle cascade):** require K live tiles before
    promotion. A build that fails to start a single tile within the window
    is NOT a new known-good.
  - **B1 (never bricks):** the deploy script treats FAIL as a rollback
    trigger, not a fatal error. The decision here just classifies; the
    caller decides what to do.
  - **B2 (always a way back):** the FAIL paths return enough detail
    (reason + counts) that the rollback log can be precise.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import time
from dataclasses import dataclass
from enum import Enum


class Outcome(str, Enum):
    PASS = "PASS"
    FAIL_NOT_READY = "FAIL_NOT_READY"
    FAIL_ALL_DEAD = "FAIL_ALL_DEAD"
    FAIL_DECODER_THRASH = "FAIL_DECODER_THRASH"
    FAIL_TIMEOUT = "FAIL_TIMEOUT"
    # Transport could not be read at all (every logcat read timed out). The
    # build is installed + byte-verified, but its health is UNVERIFIED — this is
    # NOT positive evidence of a crash, so it must never trigger a rollback.
    INDETERMINATE = "INDETERMINATE"


# Exit codes are the deploy script's contract:
EXIT_PROMOTE = 0       # PASS — promote to known-good
EXIT_ROLLBACK = 1      # confirmed crash (positive evidence) — roll back
EXIT_UNVERIFIED = 2    # indeterminate / weak evidence — fail OPEN (install stays, no rollback)


def exit_code_for(outcome: "Outcome", readable: bool) -> int:
    """Map a health decision to a deploy action — the fail-open policy.

    A rollback DESTROYS a byte-verified install, so it must fire only on
    POSITIVE evidence of a crash over a READABLE transport. When the transport
    is unreadable, or the only signal is absence-of-telemetry (which a truncated
    read over the flaky link can fake), we fail OPEN: the build stays installed
    and unpromoted, and the operator confirms the panel by eye.
    """
    if not readable:
        return EXIT_UNVERIFIED
    if outcome == Outcome.PASS:
        return EXIT_PROMOTE
    if outcome in (Outcome.FAIL_ALL_DEAD, Outcome.FAIL_DECODER_THRASH):
        return EXIT_ROLLBACK  # positive crash shape (EV=DEAD / decoder-thrash)
    # FAIL_NOT_READY (too few EV=TILE_READY) is weak/ambiguous over a flaky
    # transport — do NOT roll back a byte-verified install on it.
    return EXIT_UNVERIFIED


@dataclass(frozen=True)
class Decision:
    outcome: Outcome
    reason: str
    tile_ready: int
    decoder: int
    dead: int

    def to_dict(self) -> dict:
        return {
            "outcome": self.outcome.value,
            "reason": self.reason,
            "tile_ready_count": self.tile_ready,
            "decoder_count": self.decoder,
            "dead_count": self.dead,
            "is_promote": self.outcome == Outcome.PASS,
        }


_RE_TILE_READY = re.compile(r"\bEV=TILE_READY\b")
_RE_DECODER = re.compile(r"\bEV=DECODER\b")
_RE_DEAD = re.compile(r"\bEV=DEAD\b")


def decide(
    logcat: str,
    *,
    minimum_ready: int,
    expected_tile_count: int,
) -> Decision:
    """Pure decision: should this build be promoted?

    Args:
      logcat: the captured MYMTS_SOAK logcat blob over the health window.
      minimum_ready: how many `EV=TILE_READY` events we require to call
        the wall "up." Typically `expected_tile_count` for a strict bar;
        for a wall whose helper only resolves 2 channels, the operator
        may pass a lower number like 2.
      expected_tile_count: the wall's configured tile count. Used to
        interpret a "all tiles dead" failure shape.

    Returns:
      A [Decision] the deploy script feeds to its promote-or-rollback step.
    """
    tile_ready = len(_RE_TILE_READY.findall(logcat))
    decoder = len(_RE_DECODER.findall(logcat))
    dead = len(_RE_DEAD.findall(logcat))

    # All tiles settled DEAD within the window — the wall came up and
    # immediately failed. This is the b19b013 regression shape exactly,
    # and the kind of build we must never promote silently.
    if dead >= expected_tile_count:
        return Decision(
            outcome=Outcome.FAIL_ALL_DEAD,
            reason=(
                f"{dead} EV=DEAD events (>= expected_tile_count={expected_tile_count}) "
                "within the health window — the wall never sustained playback"
            ),
            tile_ready=tile_ready, decoder=decoder, dead=dead,
        )

    # Decoders thrashing without a single rendered frame is another
    # b19b013-shape signal: ExoPlayer initializes the decoder but no
    # surface is attached so `onRenderedFirstFrame` never fires.
    if tile_ready == 0 and decoder >= expected_tile_count:
        return Decision(
            outcome=Outcome.FAIL_DECODER_THRASH,
            reason=(
                f"{decoder} EV=DECODER events but ZERO EV=TILE_READY — "
                "decoder ran but no frame ever rendered to the surface"
            ),
            tile_ready=tile_ready, decoder=decoder, dead=dead,
        )

    if tile_ready < minimum_ready:
        return Decision(
            outcome=Outcome.FAIL_NOT_READY,
            reason=(
                f"only {tile_ready} EV=TILE_READY events; need >= {minimum_ready}"
            ),
            tile_ready=tile_ready, decoder=decoder, dead=dead,
        )

    # Pass: enough tiles reached LIVE, none have settled DEAD inside the
    # window. The build is healthy enough to be the new known-good.
    return Decision(
        outcome=Outcome.PASS,
        reason=(
            f"{tile_ready} EV=TILE_READY events (>= {minimum_ready}), "
            f"{dead} dead — wall up"
        ),
        tile_ready=tile_ready, decoder=decoder, dead=dead,
    )


def _capture_via_adb(
    device: str, deadline_seconds: int, *, read_timeout: int = 30
) -> tuple[str, bool]:
    """Stream logcat -d periodically until [deadline_seconds] elapse.

    Returns ``(blob, readable)``. Tolerant of the flaky LAN transport: each
    ``logcat -d`` read gets a generous timeout, and a timed-out/errored read is
    RETRIED within the deadline rather than crashing the script (the previous
    uncaught ``TimeoutExpired`` on a 10s read is exactly what false-failed a
    healthy deploy). ``readable`` is True iff at least one read actually
    completed — letting the caller distinguish "telemetry says X" (readable)
    from "could not read the transport at all" (indeterminate; never a rollback).

    Not unit-tested here (the unit tests exercise [decide]/[exit_code_for] on
    canned inputs); this is the device-touching capture.
    """
    import subprocess
    start = time.monotonic()
    captured: list[str] = []
    successful_reads = 0
    while time.monotonic() - start < deadline_seconds:
        try:
            proc = subprocess.run(
                ["adb", "-s", device, "logcat", "-d", "-s", "MYMTS_SOAK"],
                capture_output=True, text=True, check=False, timeout=read_timeout,
            )
            successful_reads += 1
            captured.append(proc.stdout)
        except subprocess.TimeoutExpired:
            # Transport slow/wedged for this read — do NOT crash; retry within
            # the deadline. A successful, byte-verified deploy must not roll
            # itself back just because a logcat read timed out.
            pass
        except OSError:
            pass
        time.sleep(2.5)
    return "\n".join(captured), successful_reads >= 1


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--device", required=False,
                   help="adb device IP (with port if needed). Required unless --logcat-file is given.")
    p.add_argument("--logcat-file",
                   help="Use a pre-captured logcat blob instead of running adb (dry-run / replay).")
    p.add_argument("--deadline-seconds", type=int, default=90,
                   help="How long to capture logcat before deciding (default 90s).")
    p.add_argument("--minimum-ready", type=int, default=2,
                   help="Minimum EV=TILE_READY events required to PASS.")
    p.add_argument("--expected-tile-count", type=int, default=4,
                   help="Wall's configured tile count — used for FAIL_ALL_DEAD detection.")
    p.add_argument("--read-timeout", type=int, default=30,
                   help="Per-logcat-read timeout in seconds (default 30; the flaky "
                        ".92 transport needs >>10s). Timed-out reads are retried.")
    args = p.parse_args(argv)

    if args.logcat_file:
        with open(args.logcat_file, encoding="utf-8") as f:
            blob = f.read()
        readable = True
    else:
        if not args.device:
            p.error("--device required unless --logcat-file is given")
        blob, readable = _capture_via_adb(
            args.device, args.deadline_seconds, read_timeout=args.read_timeout
        )

    if not readable:
        # Never read the transport — INDETERMINATE, not a confirmed failure.
        decision = Decision(
            outcome=Outcome.INDETERMINATE,
            reason=(
                "logcat was unreadable over the transport after retries — the build "
                "is installed + byte-verified but health is UNVERIFIED; not rolling "
                "back, confirm the wall on the panel"
            ),
            tile_ready=0, decoder=0, dead=0,
        )
    else:
        decision = decide(
            blob,
            minimum_ready=args.minimum_ready,
            expected_tile_count=args.expected_tile_count,
        )
    json.dump(decision.to_dict(), sys.stdout, indent=2)
    sys.stdout.write("\n")
    return exit_code_for(decision.outcome, readable)


if __name__ == "__main__":
    sys.exit(main())
