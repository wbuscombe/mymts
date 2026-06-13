"""Render a channel list as an M3U playlist.

The playlist is a plain channel list a standard player (VLC on an Apple
TV) can load. Each entry points at the channel's *resolved upstream* HLS
URL (`current_url`) — the helper stays the resolver/shield and NEVER
proxies the video bytes (the no-proxy decision; see `docs/BACKLOG.md`).
Only live channels are emitted; an empty set yields a valid, empty
`#EXTM3U` (honest degradation — a down lineup is an empty playlist, never
a faked one).
"""

from __future__ import annotations

from collections.abc import Iterable

from ..channels.registry import ChannelRow

# Classic M3U content type. The entries are HLS streams, but the file
# itself is a channel playlist (.m3u, not an HLS .m3u8 manifest); VLC and
# friends parse by content (`#EXTM3U`) regardless of the header.
M3U_MEDIA_TYPE = "audio/x-mpegurl"


def _one_line(value: str) -> str:
    """Collapse every line-break code point so a label can never inject a new
    playlist line. `str.splitlines()` covers the full set a text parser might
    honor — CR/LF/CRLF plus VT, FF, FS/GS/RS, NEL (U+0085) and the Unicode
    line/paragraph separators (U+2028/U+2029) — not just ASCII CR/LF."""
    return " ".join(value.splitlines()).strip()


def _attr(value: str) -> str:
    """Sanitize a value for use inside a quoted `key="..."` attribute."""
    return _one_line(value).replace('"', "")


def render_m3u(channels: Iterable[ChannelRow]) -> str:
    """Render `channels` as M3U text (always ends with a trailing newline).

    Callers pass the already-resolved, already-ordered set. Defensively we
    still skip any row that isn't live, has no URL, or has a CR/LF in its
    URL — so one bad row can never list a dead endpoint or corrupt the file
    (the DATA-2 'one bad row must not break the batch' lesson).
    """
    lines = ["#EXTM3U"]
    for c in channels:
        url = c.current_url
        if c.status != "live" or not url or "\n" in url or "\r" in url:
            continue
        extinf = (
            f'#EXTINF:-1 tvg-id="{_attr(c.slug)}" '
            f'tvg-name="{_attr(c.label)}",{_one_line(c.label)}'
        )
        lines.append(extinf)
        lines.append(url)
    return "\n".join(lines) + "\n"
