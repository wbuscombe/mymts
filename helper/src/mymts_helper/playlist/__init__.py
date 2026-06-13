"""Playlist / profile surface.

Exposes the resolved channel lineup as a standard M3U playlist a generic
player (VLC on an Apple TV) can load, narrowed/ordered by a named profile.
The helper stays the resolver/shield — the M3U points at each channel's
resolved upstream URL and the helper never proxies the video (the no-proxy
decision). LAN-only, like the rest of the helper; only live channels are
listed (honest degradation). Foundation for the cross-platform-profiles
fork (BACKLOG item H); per-client server-side state / identity / sync stay
deferred.
"""
