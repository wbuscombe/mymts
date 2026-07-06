"""Channel category taxonomy — the authoritative source the clients group by.

This is the **authoritative** section each channel groups under, served on
``/api/channels`` as ``category``. **Both** clients consume it: the LAN web
client and (since 2026-06) the native Compose wall's ``ChannelPickerOverlay``,
which now groups by this served field (``ChannelCategory.sectionedByCategory``)
instead of its own compiled slug map — so a channel added here groups correctly
on the TV with no app rebuild (the parity follow-up that closed the General-drift).
The native ``ChannelCategory.BY_SLUG`` survives only as a fallback for an older
helper. The render ORDER still mirrors native's ``ChannelCategory.ORDER``.

The category is DERIVED FROM THE SLUG (a pure function over a static map) — it is
NOT stored in the DB, so there is no migration: a channel's section is a property
of which channel it is, not mutable per-deploy state. An unmapped slug falls back
to ``GENERAL`` (the same fallback native uses), so a newly-seeded channel still
shows up — under General — rather than vanishing from the picker.
"""

from __future__ import annotations

# Section names — the picker sections. The original news sections match native's
# ChannelCategory exactly; the 2026-06 ambient sections (Cameras / Nature / Space)
# are SERVER-FIRST — the native picker shows them via sectionedByCategory (which
# appends any unrecognized server category alphabetically before General, with no
# app rebuild), and the web client lists them in CHANNEL_CATEGORY_ORDER.
SPORTS = "Sports"
US_NEWS = "US News"
GLOBAL_NEWS = "Global News"
BUSINESS = "Business"
WEATHER = "Weather"
# Weather RADAR widgets (the free public NWS radar-loop cell source) — a distinct
# section from the WEATHER video channels so the animated-image radar sources group
# together and read clearly as widgets. UNIFIED REGISTRY (2026-07): surfaced to EVERY
# picker (native TV, web /app/, /control/) — the native app renders the loop in a tile
# now, so this is no longer web-only. See weather/regions.py (the source of truth), the
# native ChannelCategory.WEATHER_RADAR, and the web CHANNEL_CATEGORY_ORDER.
WEATHER_RADAR = "Weather Radar"
# Session-gated official .gov feeds (chamber floors, committee hearings, agency
# briefings) — honest-offline when not in session. Split out of US News so that
# section reads live-dense and the gov feeds are honestly grouped (their dark-when-
# not-in-session state is expected for "Government"). Server-first like the ambient
# trio: native shows it via sectionedByCategory; the web lists it in CHANNEL_CATEGORY_ORDER.
GOVERNMENT = "Government"
CAMERAS = "Cameras"
NATURE = "Nature"
SPACE = "Space"
GENERAL = "General"

# Render order of the sections in the picker. The ambient trio is placed in the
# SAME alphabetical order (Cameras, Nature, Space, before General) that native's
# sectionedByCategory appends unrecognized categories in — so all three clients
# (this taxonomy doc, the web picker, and native) agree on the order.
CATEGORY_ORDER: list[str] = [
    # Government / Cameras / Nature / Space are server-first sections native appends
    # ALPHABETICALLY before General (it doesn't compile them), so they're placed in
    # that same alphabetical order here + in the web CHANNEL_CATEGORY_ORDER for parity.
    SPORTS, US_NEWS, GLOBAL_NEWS, BUSINESS, WEATHER, WEATHER_RADAR,
    CAMERAS, GOVERNMENT, NATURE, SPACE, GENERAL,
]

# slug -> category. The news slugs mirror native's BY_SLUG; the ambient slugs
# (Space / Nature / Cameras) are server-only — native reads them from /api/channels.
# An unmapped slug (e.g. redbull-tv) falls through to GENERAL, as native does.
_BY_SLUG: dict[str, str] = {
    "cbs-sports-hq": SPORTS,
    "cnn": US_NEWS,
    "livenow-fox": US_NEWS,
    "newsmax": US_NEWS,
    "white-house-tv": GOVERNMENT,
    "bbc-news": GLOBAL_NEWS,
    "dw-news-en": GLOBAL_NEWS,
    "france24-en": GLOBAL_NEWS,
    "sky-news": GLOBAL_NEWS,
    "bloomberg-tv": BUSINESS,
    "fox-weather": WEATHER,
    "accuweather-now": WEATHER,
    # WeatherNation re-added 2026-06-24 after its NAS-prober TLS handshake was
    # fixed (the Stirr CDN offers only an RSA-kx cipher Python's default omits;
    # fetcher now enables it WITHOUT weakening cert verification). WeatherSpy
    # added (free Rakuten/CloudFront FAST weather). Both validated 200 + #EXTM3U
    # (master→variant) from the NAS prober vantage before seeding.
    "weathernation": WEATHER,
    "weatherspy": WEATHER,
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
    # --- 2026-06 YouTube-sourced lineup (kind='youtube'; the yt-dlp resolver
    #     now turns each /live URL into an HLS manifest — see youtube_resolver.py).
    #     Each was resolved + probed from the NAS's residential vantage before
    #     seeding; non-24/7 feeds (Court TV / Law&Crime / PBS) ride the honest-
    #     offline path (shown offline between shows, live when live). Both the web
    #     AND native pickers now group these by the served `category` (the native
    #     parity follow-up shipped 2026-06), so they section correctly on the TV.
    "pbs-newshour": US_NEWS,
    "court-tv": US_NEWS,
    "law-crime": US_NEWS,
    # U.S. House + Senate floor proceedings via FREE government feeds — House via
    # the Clerk's YouTube live, Senate via the `cspan` token-free resolver
    # (senate.gov ISVP); both honest-offline when the chamber is not in session.
    # The entitlement-gated C-SPAN linear networks (Adobe Pass) are NOT sourced.
    "us-house-floor": GOVERNMENT,
    "us-senate-floor": GOVERNMENT,
    # --- 2026-06 free gov-stream generalization: committee hearings + federal events
    #     (all honest-offline between sessions/briefings; all free, token-free,
    #     DRM-free, verified live before seeding). Senate committee hearings ride the
    #     SAME `cspan` resolver via the committee hearings.xml schedule (a single
    #     aggregate tile that shows whichever Senate committee is live); House
    #     committees + federal agencies stream on their own official YouTube /live
    #     (kind='youtube', is_live-gated). Grouped under the dedicated GOVERNMENT
    #     section (2026-06-22): the chamber floors + committees + agency briefings +
    #     the White House feed live here, so US News reads live-dense and these
    #     session/event feeds are honestly grouped (dark-when-not-in-session is
    #     expected for "Government"). Native shows it via sectionedByCategory; the web
    #     lists it in CHANNEL_CATEGORY_ORDER. ---
    "us-senate-committees": GOVERNMENT,
    "us-house-oversight": GOVERNMENT,
    "us-house-judiciary": GOVERNMENT,
    "us-house-appropriations": GOVERNMENT,
    "us-house-armed-services": GOVERNMENT,
    "us-house-financial-services": GOVERNMENT,
    "us-state-dept": GOVERNMENT,
    "us-dept-of-war": GOVERNMENT,
    "us-dhs": GOVERNMENT,
    "us-doj": GOVERNMENT,
    "euronews": GLOBAL_NEWS,
    "wion": GLOBAL_NEWS,
    "ndtv": GLOBAL_NEWS,
    "i24news-en": GLOBAL_NEWS,
    "cbs-golazo": SPORTS,
    # --- 2026-06 ambient / space / nature / camera lives (restoring the original
    #     wall vision). All free official YouTube lives, is_live-gated -> honest-
    #     offline (verified live before seeding). NASA's NTV1 linear stays HLS;
    #     iss-feed re-pointed (dead ustream -> NASA's @NASA ISS-HD YouTube live). ---
    "nasa-tv": SPACE,
    "iss-feed": SPACE,
    "explore-nature-cams": NATURE,
    "monterey-aquarium": NATURE,
    "earthcam-live": CAMERAS,
    "earthtv-live": CAMERAS,
    # --- 2026-06 white-whale ocean + eagle cams (free official explore.org
    #     YouTube lives, is_live-gated -> honest-offline; Decorah eagles are
    #     SEASONAL). Categorized under the existing NATURE section so BOTH clients
    #     render them with no rebuild (the Ocean / Eagles *presets* group them by
    #     slug; a dedicated Ocean/Birds *picker* category would need a web-client
    #     change — the web folds an unknown category to General — so it's deferred,
    #     same as the logged "Government" section). Per-cam video IDs because the
    #     @exploreLiveNatureCams /live handle rotates through cams; a dead/rotated
    #     ID honest-offlines (never fake-live) until re-pointed. ---
    "tropical-reef": NATURE,
    "manatee-cam": NATURE,
    "decorah-eagles": NATURE,
    # Still honestly OMITTED:
    #   • Arirang → could NOT confirm a live HLS from the NAS vantage (every
    #     candidate handle 404'd or reported not-live); omitted to ship no dead
    #     channel — revisit when a stable live handle is confirmed.
    #   • Paywall / cable-auth: CNN, Fox News, MSNBC, CBS Sports Network.
    #   • Web-embed only (no HLS): C-SPAN 2 & 3.
    #   • cnn-international (PRUNED 2026-06-24) → its Wurl/Rakuten FAST host is
    #     DNS-dead and CNN International is no longer on any US FAST platform
    #     (Pluto's "CNN Headlines"/"CNN Originals" are different curated channels,
    #     not the international linear feed). No clean free source — verdict on
    #     BACKLOG. The free C-SPAN path is the gov feeds below, not this.
    #   • c-span (the branded cspan1 akamai, PRUNED 2026-06-24) → now http_403;
    #     the three linear C-SPAN networks online are MVPD-login-gated, and the
    #     only token-FREE C-SPAN path is the government event streams — which ARE
    #     built (the `us-senate-floor` / `us-senate-committees` cspan resolvers,
    #     unaffected by this prune). No token-free cspan1 linear HLS — BACKLOG.
}


def category_of(slug: str) -> str:
    """Return the section a channel slug belongs to (GENERAL if unmapped)."""
    return _BY_SLUG.get(slug, GENERAL)
