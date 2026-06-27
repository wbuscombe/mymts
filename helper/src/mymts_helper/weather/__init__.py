"""Weather widgets for the wall — currently the NWS radar-loop cell source.

A radar cell is a SELECTABLE per-cell source ("pseudo-channel"): the wall config
stores a slug like ``weather-radar-kilx`` exactly as it stores a video channel's
slug, so radar rides the existing per-cell picker + partial-merge with no new
config field. ``regions`` is the single source of truth for the region set;
``api`` proxies + caches the free public NWS imagery; ``cache`` holds the frames.
"""
