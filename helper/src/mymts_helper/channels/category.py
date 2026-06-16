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
    # --- 2026-06 lineup expansion (free 24/7 direct-HLS origins; each validated
    #     HTTP 200 + #EXTM3U from the NAS prober's US vantage before seeding,
    #     matching the docs/findings/05 sourcing standard). Existing channels
    #     stay first in their category (lower-id / earlier-seed = prominent). ---
    "abc-news-live": US_NEWS,
    "nbc-news-now": US_NEWS,
    "news-nation": US_NEWS,
    "scripps-news": US_NEWS,
    "abc-news-au": GLOBAL_NEWS,
    "cna": GLOBAL_NEWS,
    "gb-news": GLOBAL_NEWS,
    "nhk-world": GLOBAL_NEWS,
    # Honestly OMITTED (no stable direct-HLS origin via the helper's direct-HLS-only
    # model — there is no yt-dlp resolver; kind='youtube' is a future migration):
    #   • YouTube-live-only (no direct HLS): PBS NewsHour, C-SPAN main, Court TV,
    #     Law & Crime.
    #   • Tokenized / third-party-redirect only (against findings/05's direct-origin
    #     principle): CBS News 24/7, Euronews English, i24NEWS (expiring JWT).
    #   • No clean public endpoint: WION, NDTV 24x7 (community redirect file only),
    #     CBS Sports Golazo; Arirang (http:// only — fails HTTPS-only).
    #   • Paywall / cable-auth: CNN, Fox News, MSNBC, CBS Sports Network.
    #   • Web-embed only (no HLS): C-SPAN 2 & 3.
    #   • Known NAS-prober TLS handshake failure: WeatherNation.
}


def category_of(slug: str) -> str:
    """Return the section a channel slug belongs to (GENERAL if unmapped)."""
    return _BY_SLUG.get(slug, GENERAL)
