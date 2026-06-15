"""Channel category taxonomy — the authoritative source the clients group by.

This MIRRORS the native app's ``ChannelCategory`` (the Compose wall's
``ChannelPickerOverlay`` groups its picker by exactly these sections, in this
order). The native side has its own copy keyed by slug; the helper exposes the
SAME taxonomy on ``/api/channels`` so the LAN web client can build an identical
sectioned picker without inventing its own categories. Keeping the mapping
server-side means both browser clients agree on one taxonomy; the native app
keeps its own copy until it, too, consumes the served field (a parity follow-up).

The category is DERIVED FROM THE SLUG (a pure function over a static map) — it is
NOT stored in the DB, so there is no migration: a channel's section is a property
of which channel it is, not mutable per-deploy state. An unmapped slug falls back
to ``GENERAL`` (the same fallback native uses), so a newly-seeded channel still
shows up — under General — rather than vanishing from the picker.
"""

from __future__ import annotations

# Section names — EXACTLY the native ChannelCategory constants (do not rename;
# the web client orders by these strings and native uses the same labels).
SPORTS = "Sports"
US_NEWS = "US News"
GLOBAL_NEWS = "Global News"
BUSINESS = "Business"
WEATHER = "Weather"
GENERAL = "General"

# Render order of the sections in the picker (native ChannelCategory.ORDER).
CATEGORY_ORDER: list[str] = [SPORTS, US_NEWS, GLOBAL_NEWS, BUSINESS, WEATHER, GENERAL]

# slug -> category — a faithful copy of the native BY_SLUG map. Unmapped slugs
# (nasa-tv, iss-feed, redbull-tv) fall through to GENERAL, exactly as native does.
_BY_SLUG: dict[str, str] = {
    "cbs-sports-hq": SPORTS,
    "cnn": US_NEWS,
    "livenow-fox": US_NEWS,
    "newsmax": US_NEWS,
    "c-span": US_NEWS,
    "white-house-tv": US_NEWS,
    "bbc-news": GLOBAL_NEWS,
    "al-jazeera-en": GLOBAL_NEWS,
    "dw-news-en": GLOBAL_NEWS,
    "france24-en": GLOBAL_NEWS,
    "cnn-international": GLOBAL_NEWS,
    "sky-news": GLOBAL_NEWS,
    "cgtn-en": GLOBAL_NEWS,
    "trt-world": GLOBAL_NEWS,
    "bloomberg-tv": BUSINESS,
    "cnbc": BUSINESS,
    "fox-weather": WEATHER,
    "accuweather-now": WEATHER,
}


def category_of(slug: str) -> str:
    """Return the section a channel slug belongs to (GENERAL if unmapped)."""
    return _BY_SLUG.get(slug, GENERAL)
