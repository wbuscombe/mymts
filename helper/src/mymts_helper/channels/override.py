"""Per-deployment lineup override — the last distribution make-or-break.

A downstream self-hoster can customize the channel lineup WITHOUT editing the
shipped ``seed.json`` or rebuilding: an OPTIONAL operator file ``lineup.local.json``
in the writable data dir (``/data`` in the container; gitignored). The helper loads
the shipped seed (the curated default), then reconciles the override on top:

    {
      "add":      [ {"slug","label","kind","source_url","category"?}, ... ],
      "disable":  ["slug", ...],
      "override": { "slug": {"label"?,"category"?,"source_url"?,"kind"?}, ... }
    }

Reconcile precedence (explicit): ``override`` field-merges win over the shipped
values; ``disable`` forces ``enabled=False`` (excluded from /api/channels + the
prober); ``add`` appends new channels (a collision with a shipped slug is skipped —
use ``override`` for shipped changes). **No override file → identical to today.**

Validation: every effective channel is upserted through the SAME registry
validators (slug + kind + url) and probed like any channel — a bad entry is
SKIPPED with a logged reason, never crashing the whole lineup. A malformed override
file is logged and ignored (the shipped lineup still loads).
"""

from __future__ import annotations

import json
import logging
from pathlib import Path
from typing import Any

from .registry import RegistryError, upsert_channel

log = logging.getLogger("mymts_helper.channels.override")

# The override fields a shipped channel may have re-written (NOT slug — that's the key).
_OVERRIDABLE = ("label", "category", "source_url", "kind")
# The keys the override document may carry.
_OVERRIDE_KEYS = ("add", "disable", "override")


def load_override(path: Path | None) -> dict | None:
    """Load + shape-check the operator override file. Returns the parsed dict, or
    None when absent/empty/malformed (logged) — the shipped lineup then loads
    unchanged. Never raises."""
    if path is None or not path.exists():
        return None
    try:
        data = json.loads(path.read_text())
    except (OSError, json.JSONDecodeError) as e:
        log.warning("lineup_override_unreadable", extra={"path": str(path), "reason": str(e)[:120]})
        return None
    if not isinstance(data, dict):
        log.warning("lineup_override_not_object", extra={"path": str(path)})
        return None
    # Tolerate unknown top-level keys (forward-compat) but only act on the known ones.
    add = data.get("add") if isinstance(data.get("add"), list) else []
    disable = data.get("disable") if isinstance(data.get("disable"), list) else []
    override = data.get("override") if isinstance(data.get("override"), dict) else {}
    if not (add or disable or override):
        return None
    return {"add": add, "disable": disable, "override": override}


def reconcile(shipped: list[dict], override: dict | None) -> list[dict[str, Any]]:
    """Pure: produce the effective lineup (list of channel dicts with `enabled`)
    by applying the override on top of the shipped seed. No I/O, no validation —
    the caller upserts each (validation + skip happen there)."""
    override = override or {}   # tolerate a partial dict (missing keys) or None
    disable = {str(s) for s in (override.get("disable") or [])}
    overrides = override.get("override") or {}
    adds = override.get("add") or []

    out: list[dict[str, Any]] = []
    shipped_slugs: set[str] = set()
    for ch in shipped:
        if not isinstance(ch, dict) or "slug" not in ch:
            continue
        slug = str(ch["slug"])
        shipped_slugs.add(slug)
        eff = dict(ch)
        eff["enabled"] = slug not in disable
        ov = overrides.get(slug) if isinstance(overrides, dict) else None
        if isinstance(ov, dict):
            for k in _OVERRIDABLE:
                if k in ov:
                    eff[k] = ov[k]
        out.append(eff)

    added: set[str] = set()
    for a in adds or []:
        if not isinstance(a, dict) or "slug" not in a:
            log.warning("lineup_add_skipped_no_slug")
            continue
        slug = str(a["slug"])
        if slug in shipped_slugs or slug in added:
            log.warning("lineup_add_skipped_collision", extra={"slug": slug})
            continue
        eff = dict(a)
        eff["enabled"] = True
        out.append(eff)
        added.add(slug)
    return out


def seed_lineup(conn, shipped_path: Path, override_path: Path | None) -> dict:
    """Load the shipped seed + the optional override, reconcile, and upsert each
    effective channel. A bad entry (validation / missing field) is skipped with a
    logged reason — never crashes the lineup. Returns a small summary."""
    try:
        shipped = json.loads(shipped_path.read_text())
    except (OSError, json.JSONDecodeError) as e:
        raise RegistryError(f"shipped_seed_invalid: {e}") from None
    if not isinstance(shipped, list):
        raise RegistryError("shipped_seed_not_list")

    override = load_override(override_path)
    effective = reconcile(shipped, override)

    seeded = skipped = disabled = 0
    seeded_slugs: list[str] = []
    for ch in effective:
        try:
            slug = str(ch["slug"])
            upsert_channel(
                conn,
                slug=slug,
                label=str(ch["label"]),
                source_url=str(ch["source_url"]),
                kind=str(ch.get("kind", "hls")),
                category=(str(ch["category"]) if ch.get("category") else None),
                enabled=bool(ch.get("enabled", True)),
            )
            seeded += 1
            seeded_slugs.append(slug)
            if not ch.get("enabled", True):
                disabled += 1
        except (RegistryError, KeyError, TypeError) as e:
            log.warning("lineup_entry_skipped",
                        extra={"slug": ch.get("slug"), "reason": str(e)[:120]})
            skipped += 1

    # Prune ORPHANS — channels in the DB that are no longer in the effective lineup
    # (e.g. an `add` later removed from the override, or a slug dropped from the
    # shipped seed). This is what makes removing an override revert cleanly. GUARDED:
    # never prune when nothing seeded (an empty/unreadable seed) — that would wipe
    # the lineup. A disabled shipped channel stays (it IS in the effective set,
    # enabled=0), so only true orphans are removed.
    pruned = 0
    if seeded_slugs:
        placeholders = ",".join("?" * len(seeded_slugs))
        cur = conn.execute(
            # noqa justified: the f-string injects only `?` placeholders (count =
            # len(seeded_slugs)); every value is bound — no string interpolation.
            f"DELETE FROM channels WHERE slug NOT IN ({placeholders})",  # noqa: S608
            seeded_slugs,
        )
        pruned = cur.rowcount or 0
        if pruned:
            log.info("lineup_pruned_orphans", extra={"count": pruned})
    return {
        "seeded": seeded,
        "skipped": skipped,
        "disabled": disabled,
        "pruned": pruned,
        "override_applied": override is not None,
    }
