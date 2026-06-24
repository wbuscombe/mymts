"""Server-side wall config — the headless container's wall state.

A headless wall (the NAS-container version) has no device to hold its lineup,
so the wall's LAYOUT + per-cell channel + per-cell subtitle + single-audible-
cell state lives helper-side. This is the natural home for the headless
version: the native app stays device-local (its `LineupStore`), so this
resolves the deferred cross-device-profile question **server-side out of
necessity for this context** — no conflict with the native model.
"""
