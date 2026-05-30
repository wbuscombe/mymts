#!/usr/bin/env python3
"""Summarize a MyMTS soak run for a finding-doc table.

Usage: scripts/parse-soak-log.py docs/findings/runs/<run-id>/

Reads events.log + meminfo.csv from the run directory, prints a compact
markdown summary suitable for pasting into docs/findings/01-onn4k-tile-budget.md.

Pure-stdlib; no deps.
"""

from __future__ import annotations

import argparse
import csv
import json
import re
import statistics
import sys
from collections import Counter, defaultdict
from pathlib import Path

EVENT_RE = re.compile(r"EV=(?P<name>\w+)\|(?P<rest>.*)$")
KV_RE = re.compile(r"(\w+)=([^|]+)")


def parse_event_line(line: str) -> tuple[str, dict[str, str]] | None:
    m = EVENT_RE.search(line)
    if not m:
        return None
    fields = dict(KV_RE.findall(m.group("rest")))
    return m.group("name"), fields


def summarize_events(events_path: Path) -> dict:
    counts: Counter[str] = Counter()
    dropped_by_tile: defaultdict[str, int] = defaultdict(int)
    errors_by_tile: defaultdict[str, list[str]] = defaultdict(list)
    decoder_by_tile: dict[str, str] = {}
    first_ready: dict[str, int] = {}
    start_ts_ms: int | None = None

    with events_path.open() as f:
        for line in f:
            parsed = parse_event_line(line)
            if not parsed:
                continue
            name, fields = parsed
            counts[name] += 1
            if name == "START":
                # logcat timestamp parsing skipped — start_ts is the first event in the file.
                pass
            elif name == "TILE_READY":
                tid = fields.get("id", "?")
                ts = int(fields.get("ts_ms", "0"))
                first_ready.setdefault(tid, ts)
                if start_ts_ms is None:
                    start_ts_ms = ts
            elif name == "DROPPED":
                tid = fields.get("id", "?")
                dropped_by_tile[tid] += int(fields.get("dropped", "0"))
            elif name == "ERROR":
                tid = fields.get("id", "?")
                errors_by_tile[tid].append(fields.get("code", "?"))
            elif name == "DECODER":
                tid = fields.get("id", "?")
                decoder_by_tile[tid] = fields.get("decoder", "?")

    return {
        "counts": dict(counts),
        "dropped_by_tile": dict(dropped_by_tile),
        "errors_by_tile": {k: v for k, v in errors_by_tile.items()},
        "decoder_by_tile": decoder_by_tile,
        "first_ready_ts_ms": first_ready,
        "earliest_ready_ts_ms": start_ts_ms,
    }


def summarize_meminfo(csv_path: Path) -> dict:
    pss: list[int] = []
    java: list[int] = []
    native: list[int] = []
    graphics: list[int] = []
    with csv_path.open() as f:
        reader = csv.DictReader(f)
        for row in reader:
            try:
                pss.append(int(row["total_pss_kb"]))
                java.append(int(row["java_heap_kb"]))
                native.append(int(row["native_heap_kb"]))
                graphics.append(int(row["graphics_kb"]))
            except (KeyError, ValueError):
                continue
    if not pss:
        return {"samples": 0}
    return {
        "samples": len(pss),
        "pss_kb": {
            "first": pss[0], "last": pss[-1],
            "min": min(pss), "max": max(pss),
            "median": int(statistics.median(pss)),
            "delta_first_to_last": pss[-1] - pss[0],
        },
        "java_kb": {
            "first": java[0], "last": java[-1],
            "min": min(java), "max": max(java),
            "median": int(statistics.median(java)),
            "delta_first_to_last": java[-1] - java[0],
        },
        "native_kb": {
            "first": native[0], "last": native[-1],
            "min": min(native), "max": max(native),
            "median": int(statistics.median(native)),
            "delta_first_to_last": native[-1] - native[0],
        },
        "graphics_kb": {
            "first": graphics[0], "last": graphics[-1],
            "min": min(graphics), "max": max(graphics),
            "median": int(statistics.median(graphics)),
            "delta_first_to_last": graphics[-1] - graphics[0],
        },
    }


def kb_to_mb(kb: int) -> str:
    return f"{kb / 1024:.1f}MB"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("rundir", type=Path)
    parser.add_argument("--json", action="store_true",
                        help="emit raw JSON instead of a markdown summary")
    args = parser.parse_args()

    if not args.rundir.is_dir():
        print(f"not a directory: {args.rundir}", file=sys.stderr)
        return 2

    meta_path = args.rundir / "meta.json"
    events_path = args.rundir / "events.log"
    csv_path = args.rundir / "meminfo.csv"

    meta = json.loads(meta_path.read_text()) if meta_path.exists() else {}
    events = summarize_events(events_path) if events_path.exists() else {}
    mem = summarize_meminfo(csv_path) if csv_path.exists() else {}

    out = {"meta": meta, "events": events, "meminfo": mem}
    if args.json:
        print(json.dumps(out, indent=2, default=str))
        return 0

    print(f"# Soak run `{meta.get('run_id', args.rundir.name)}`\n")
    print(
        f"- Device: `{meta.get('device', '?')}`  ·  Tiles: **{meta.get('tiles', '?')}**  "
        f"·  Pool: `{meta.get('pool', '?')}`  ·  Resolution: `{meta.get('resolution_hint', '?')}`"
    )
    print(f"- Started: `{meta.get('started_at', '?')}`  ·  Duration: `{meta.get('duration_seconds', '?')}s`\n")

    if mem.get("samples"):
        print("## Memory (host-sampled `dumpsys meminfo` every "
              f"{meta.get('meminfo_interval_seconds', '?')}s, {mem['samples']} samples)\n")
        print("| metric | first | last | min | max | median | delta |")
        print("|---|---|---|---|---|---|---|")
        for label, key in (("Total PSS", "pss_kb"), ("Java Heap", "java_kb"),
                           ("Native Heap", "native_kb"), ("Graphics", "graphics_kb")):
            s = mem[key]
            print(f"| {label} | {kb_to_mb(s['first'])} | {kb_to_mb(s['last'])} | "
                  f"{kb_to_mb(s['min'])} | {kb_to_mb(s['max'])} | {kb_to_mb(s['median'])} | "
                  f"{s['delta_first_to_last']:+d}KB |")
        print()

    if events.get("counts"):
        print("## Events\n")
        for name, n in sorted(events["counts"].items()):
            print(f"- `{name}`: {n}")
        print()

    if events.get("dropped_by_tile"):
        print("## Dropped frames by tile\n")
        for tid, n in sorted(events["dropped_by_tile"].items()):
            print(f"- `{tid}`: {n}")
        print()

    if events.get("errors_by_tile"):
        print("## Errors by tile\n")
        for tid, codes in sorted(events["errors_by_tile"].items()):
            cs = Counter(codes)
            tally = ", ".join(f"{c}×{n}" for c, n in cs.most_common())
            print(f"- `{tid}`: {tally}")
        print()

    if events.get("decoder_by_tile"):
        print("## Decoders selected\n")
        for tid, dec in sorted(events["decoder_by_tile"].items()):
            print(f"- `{tid}`: `{dec}`")
        print()

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
