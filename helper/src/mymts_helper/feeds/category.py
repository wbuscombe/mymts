"""Feed-source category taxonomy — groups the news/sports RSS sources into the
SAME section names the channel picker uses (US News / Global News / Business /
Sports / Weather / General), so the web client can present the feed-source filter
grouped by category instead of one flat list.

This is a SEPARATE map from `channels/category.py` (feed sources are RSS outlets,
not video channels), but it deliberately reuses the same category names + order
so both surfaces read identically. Derived from the source LABEL (a static map),
served additively on each /api/feed item as `source_category` — no DB column.
"""

from __future__ import annotations

# Reuse the channel-picker category names so both surfaces read identically.
from ..channels.category import BUSINESS, GENERAL, GLOBAL_NEWS, SPORTS, US_NEWS

# label -> category. Matches helper/src/mymts_helper/feeds/seed.json. An unmapped
# label falls through to GENERAL (a newly-added source still groups, never drops).
_BY_LABEL: dict[str, str] = {
    # Global News — international desks.
    "BBC World": GLOBAL_NEWS,
    "Al Jazeera": GLOBAL_NEWS,
    "Guardian World": GLOBAL_NEWS,
    "NPR World": GLOBAL_NEWS,
    # US News — US outlets + public broadcasters + opinion/policy.
    "PBS NewsHour": US_NEWS,
    "Christian Science Monitor": US_NEWS,
    "CBS News": US_NEWS,
    "NBC News": US_NEWS,
    "Politico": US_NEWS,
    "The Dispatch": US_NEWS,
    "National Review": US_NEWS,
    "Reason": US_NEWS,
    # Business / markets.
    "Bloomberg Markets": BUSINESS,
    # Sports — ESPN league desks.
    "NFL": SPORTS,
    "NCAAF": SPORTS,
    "UFL": SPORTS,
    "NBA": SPORTS,
    "WNBA": SPORTS,
    "NCAAB": SPORTS,
    "MLB": SPORTS,
    "NHL": SPORTS,
}


def source_category(label: str) -> str:
    """Return the section a feed source label belongs to (GENERAL if unmapped)."""
    return _BY_LABEL.get(label, GENERAL)
