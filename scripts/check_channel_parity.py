#!/usr/bin/env python3
"""CI guard — the channel picker must have PARITY across every surface.

The unified registry (2026-07) rule: the native TV picker, the web `/app/` picker, and
`/control/` all derive their channel list from the ONE `/api/channels` response with **zero
surface-specific filtering** — no surface may silently omit a channel or a whole category
(e.g. the weather-radar WIDGETs) that another surface shows. Honest capability degradation
is fine (a non-video widget can't be an M3U stream), but it must be "listed everywhere, HOW
rendered may differ" — never "listed on the web, invisible on the TV."

This guard makes that structural, so a future edit can't silently reopen the split it took a
whole pass to close. It checks two things, over the source of truth in each language:

  1. SECTION TAXONOMY IDENTITY. The ordered section list is the same object on all three
     surfaces:
        helper  channels/category.py   CATEGORY_ORDER
        web     web/js/render.mjs       CHANNEL_CATEGORY_ORDER
        native  ui/menu/ChannelCategory.kt  ORDER
     If they differ (a section added to one but not the others, or a different order), a
     channel in that section groups inconsistently — the exact drift this locks out.

  2. NO WIDGET GATE. No surface has a membership-changing `widgets` param — the helper
     endpoint takes none, and the web client fetches the plain `/api/channels`. A
     reintroduced `?widgets=1`/`widgets: bool` is the old per-surface split coming back.

Self-contained (regex, no imports) so it runs from the repo root like
check_schema_consistency.py. Fails the build on any mismatch.
"""
from __future__ import annotations

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]

HELPER_CATEGORY = ROOT / "helper/src/mymts_helper/channels/category.py"
WEB_RENDER = ROOT / "web/js/render.mjs"
NATIVE_CATEGORY = ROOT / "app/src/main/java/com/mymts/ui/menu/ChannelCategory.kt"
HELPER_API = ROOT / "helper/src/mymts_helper/channels/api.py"
WEB_API = ROOT / "web/js/api.mjs"


def _const_map(text: str, pattern: str) -> dict[str, str]:
    """Map NAME -> "value" for `<pattern> NAME = "value"` const declarations."""
    return dict(re.findall(pattern, text))


def _array_block(text: str, opener: str) -> str:
    """The text between `opener` and its matching closing bracket `]` or `)`."""
    start = text.index(opener) + len(opener)
    close = ")" if opener.rstrip().endswith("(") else "]"
    end = text.index(close, start)
    return text[start:end]


def helper_order() -> list[str]:
    text = HELPER_CATEGORY.read_text()
    names = _const_map(text, r'(\w+)\s*=\s*"([^"]+)"')
    block = _array_block(text, "CATEGORY_ORDER: list[str] = [")
    return [names[n] for n in re.findall(r"\b([A-Z_]+)\b", block) if n in names]


def web_order() -> list[str]:
    block = _array_block(WEB_RENDER.read_text(), "CHANNEL_CATEGORY_ORDER = [")
    return re.findall(r'"([^"]+)"', block)


def native_order() -> list[str]:
    text = NATIVE_CATEGORY.read_text()
    names = _const_map(text, r'const val (\w+)\s*=\s*"([^"]+)"')
    block = _array_block(text, "val ORDER: List<String> = listOf(")
    return [names[n] for n in re.findall(r"\b([A-Z_]+)\b", block) if n in names]


def main() -> int:
    errors: list[str] = []

    surfaces = {}
    for name, fn in (("helper", helper_order), ("web", web_order), ("native", native_order)):
        try:
            surfaces[name] = fn()
        except (ValueError, KeyError, OSError) as e:
            errors.append(f"could not extract the section order for {name}: {e!r}")
    for name, order in surfaces.items():
        print(f"{name:7} section order: {order}")

    if len(surfaces) == 3 and len({tuple(o) for o in surfaces.values()}) > 1:
        errors.append(
            "SECTION TAXONOMY MISMATCH across surfaces — the picker would group/order "
            "channels inconsistently:\n"
            + "\n".join(f"    {n}: {o}" for n, o in surfaces.items())
        )

    # No membership-changing widget gate anywhere.
    helper_api = HELPER_API.read_text()
    if re.search(r"def list_channels\([^)]*widgets", helper_api):
        errors.append("helper channels/api.py list_channels still takes a `widgets` param "
                      "(the per-surface radar gate must stay removed)")
    web_api = WEB_API.read_text()
    if re.search(r"/api/channels\?[^`\"')]*widgets", web_api):
        errors.append("web api.mjs still requests /api/channels?widgets=… "
                      "(it must fetch the unified endpoint, no widget gate)")

    if errors:
        print("\nFAIL:")
        for e in errors:
            print("  -", e)
        return 1
    print(f"\nOK — all three surfaces agree on {len(surfaces['helper'])} sections, "
          "and no surface gates the widget channels.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
