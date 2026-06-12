#!/usr/bin/env python3
"""CI guard — the wire schema_version must agree across components.

The helper EMITS schema_version on every envelope; the native app HARD-REJECTS a
payload whose version != its expected constant. If those drift, a schema bump
silently breaks a consumer (review ARCH-1). This fails the build on a mismatch.

The web client (web/js) currently has NO schema_version guard — it reads field
names positionally, so a non-additive bump renders silently-wrong on the web
demo. That's reported here (not failed) until the web guard lands.
"""
from __future__ import annotations

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]


def grab(rel: str, pattern: str) -> int | None:
    p = ROOT / rel
    if not p.exists():
        return None
    m = re.search(pattern, p.read_text())
    return int(m.group(1)) if m else None


HELPER = {
    "ticker/__init__.py": grab("helper/src/mymts_helper/ticker/__init__.py", r"TICKER_SCHEMA_VERSION\s*=\s*(\d+)"),
    "channels/api.py": grab("helper/src/mymts_helper/channels/api.py", r"API_SCHEMA_VERSION\s*=\s*(\d+)"),
    "feeds/api.py": grab("helper/src/mymts_helper/feeds/api.py", r"API_SCHEMA_VERSION\s*=\s*(\d+)"),
    "health.py": grab("helper/src/mymts_helper/health.py", r"SCHEMA_VERSION\s*=\s*(\d+)"),
}
APP = grab("app/src/main/java/com/mymts/data/helper/HelperClient.kt", r"SUPPORTED_SCHEMA_VERSION\s*=\s*(\d+)")

web_api = ROOT / "web/js/api.mjs"
WEB_HAS_GUARD = bool(web_api.exists() and re.search(r"schema_version", web_api.read_text(), re.I))


def main() -> int:
    print("helper schema_version constants:", HELPER)
    print("app  SUPPORTED_SCHEMA_VERSION   :", APP)

    errors: list[str] = []
    for name, v in HELPER.items():
        if v is None:
            errors.append(f"could not find a schema_version constant in helper {name}")
    if APP is None:
        errors.append("could not find SUPPORTED_SCHEMA_VERSION in HelperClient.kt")

    vals = {v for v in HELPER.values() if v is not None}
    if APP is not None:
        vals.add(APP)
    if len(vals) > 1:
        errors.append(f"schema_version MISMATCH across components: {sorted(vals)} (helper={HELPER}, app={APP})")

    if not WEB_HAS_GUARD:
        print(
            "NOTE: web client (web/js/api.mjs) has no schema_version guard (review ARCH-1) — "
            "a non-additive bump would render silently-wrong on the web demo. Tracked, not failing CI."
        )

    if errors:
        print("\nFAIL:")
        for e in errors:
            print("  -", e)
        return 1
    print(f"\nOK — all checked consumers agree on schema_version={vals.pop()}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
