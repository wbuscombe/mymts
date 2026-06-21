"""Server-authoritative wall presets — switchable channel-sets the user applies to
the grid (News Wall / Nature / Space / Chill). The helper DEFINES the presets and
serves them on ``/api/presets``; both clients render the SAME set, and a new preset
flows with NO client rebuild (the server-authoritative pattern, mirroring categories).

**Default = News Wall, no regression.** A client whose active preset is ``news``
uses its EXISTING default lineup (the curated news set — native ``LineupSelector``,
web ``WEB_DEFAULT_LINEUP``), so the wall is IDENTICAL to today until the user
switches. Non-default presets are ``"exact"`` — only their listed slugs fill the
grid (curated, no top-up); ``news`` is ``"topup"`` (preferred + remaining playable,
today's behavior). ``grid`` is the suggested rows×cols applied on selection.

Presets reference slugs ONLY. An honest-offline channel inside a preset (e.g. NASA
TV in Space) is fine — a preset is a SELECTION, not a liveness claim; the channel
still honest-offlines on the wall.
"""

from __future__ import annotations

from typing import Any

from fastapi import APIRouter

PRESETS_SCHEMA_VERSION = 1
DEFAULT_PRESET_ID = "news"

# Ordered preset definitions. `slugs` reference channels in seed.json (a test pins
# that every slug is real). `news` mirrors the native PREFERRED / web
# WEB_DEFAULT_LINEUP — keep the three in sync.
PRESETS: list[dict[str, Any]] = [
    {
        "id": "news", "name": "News Wall", "fill": "topup",
        "grid": {"rows": 2, "cols": 2},
        "slugs": ["livenow-fox", "fox-weather", "bbc-news", "cbs-sports-hq",
                  "bloomberg-tv", "cnbc", "cnn"],
    },
    {
        "id": "nature", "name": "Nature", "fill": "exact",
        "grid": {"rows": 2, "cols": 2},
        "slugs": ["explore-nature-cams", "monterey-aquarium", "earthcam-live", "earthtv-live"],
    },
    {
        "id": "space", "name": "Space", "fill": "exact",
        "grid": {"rows": 1, "cols": 2},
        "slugs": ["iss-feed", "nasa-tv"],   # ISS HD live + NASA TV (honest-offline ok)
    },
    {
        "id": "chill", "name": "Chill / Mixed", "fill": "exact",
        "grid": {"rows": 2, "cols": 3},
        "slugs": ["explore-nature-cams", "monterey-aquarium", "iss-feed",
                  "earthcam-live", "fox-weather", "bloomberg-tv"],
    },
    {
        # Ocean / reef — explore.org Tropical Reef + the existing Monterey Bay
        # Aquarium + the Homosassa underwater Manatee cam. All free official
        # YouTube lives, is_live-gated -> honest-offline if a cam is down.
        "id": "ocean", "name": "Ocean", "fill": "exact",
        "grid": {"rows": 1, "cols": 3},
        "slugs": ["tropical-reef", "monterey-aquarium", "manatee-cam"],
    },
    {
        # Eagles — the famous explore.org / Raptor Resource Project Decorah eagle
        # nest. SEASONAL: live through nesting season, honest-OFFLINE off-season
        # via the is_live gate (the preset still resolves; the tile shows the cam
        # is down — correct, never faked). A single focused fullscreen cam.
        "id": "eagles", "name": "Eagles", "fill": "exact",
        "grid": {"rows": 1, "cols": 1},
        "slugs": ["decorah-eagles"],
    },
]


def get_router() -> APIRouter:
    router = APIRouter(prefix="/api/presets", tags=["presets"])

    @router.get("")
    def list_presets() -> dict[str, Any]:
        # Additive endpoint — does not touch the /api/channels contract. Its own
        # schema_version; the clients render whatever presets are served (a new one
        # appears with no rebuild).
        return {
            "schema_version": PRESETS_SCHEMA_VERSION,
            "default": DEFAULT_PRESET_ID,
            "presets": PRESETS,
        }

    return router
