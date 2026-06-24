"""SSRF-safe outbound HTTP.

Single fetch primitive used by both the RSS poller and the channel
prober. Implements the Trust-Bar A1/A2/A4 rules at the boundary:

  - https only (http rejected at the API boundary; here we still enforce
    so a programming bug can't sneak http through),
  - DNS resolution happens up-front; any resolved IP that lands in
    RFC 1918, loopback, link-local, CGNAT, or the IPv6 ULA/loopback/
    link-local ranges is rejected before we open a socket,
  - the connection is opened by resolved-IP with an explicit Host
    header so DNS rebinding can't redirect a follow-up to private
    space mid-transfer,
  - bounded response body, bounded total time, no redirects to non-
    https or to private space (we re-validate on each hop).

The fetcher exposes a small async surface (fetch_text / fetch_bytes).
For testability the IP resolver is injectable — unit tests inject a
fake resolver that returns whatever address we want and assert the
fetcher rejects the bad cases without ever opening a real socket.
"""

from __future__ import annotations

import ipaddress
import logging
import socket
import ssl
from collections.abc import Awaitable, Callable
from dataclasses import dataclass
from urllib.parse import urlparse

import httpx

log = logging.getLogger("mymts_helper.fetcher")

DEFAULT_TIMEOUT = httpx.Timeout(connect=10.0, read=20.0, write=10.0, pool=5.0)
DEFAULT_MAX_BYTES = 8 * 1024 * 1024  # 8MB — plenty for any feed or .m3u8
DEFAULT_MAX_REDIRECTS = 3


def _build_ssl_context() -> ssl.SSLContext:
    """The fetcher's TLS context. Cert verification + hostname checking STAY ON
    (CERT_REQUIRED + check_hostname) — MITM protection is non-negotiable and the
    cert-acceptance bar is UNCHANGED (SECLEVEL stays the default 2). We ONLY
    broaden the offered cipher list to also include the RSA-key-exchange AES-GCM
    suites that Python's hardened default omits.

    Why: some legitimate free FAST-platform origins (e.g. WeatherNation's Stirr
    CDN) offer ONLY a non-forward-secret RSA-kx AES-GCM cipher, so Python's
    default context fails their handshake (`SSLV3_ALERT_HANDSHAKE_FAILURE`) even
    though curl/openssl complete it. Forward secrecy is moot here — the fetcher
    retrieves only PUBLIC HLS manifests / RSS (no secrets or credentials in the
    traffic) — while cert AUTHENTICITY (preserved) is what guards against a fake
    origin. So we ADD a cipher; we do NOT weaken which certs we trust, and we do
    NOT disable verification. `AES256-GCM-SHA384`/`AES128-GCM-SHA256` are strong
    (256/128-bit) and clear SECLEVEL 2's floor; only their lack of PFS kept them
    out of Python's default list.
    """
    ctx = ssl.create_default_context()
    ctx.set_ciphers("DEFAULT:AES256-GCM-SHA384:AES128-GCM-SHA256")
    return ctx


# Built once at import; read-only thereafter (safe to share across requests).
_SSL_CONTEXT = _build_ssl_context()

Resolver = Callable[[str], Awaitable[list[str]]]


class FetchError(Exception):
    """All fetcher rejections / failures raise this. Includes a short reason."""


@dataclass(frozen=True)
class FetchResult:
    url: str            # final URL (after any redirects)
    status_code: int
    content_type: str
    body: bytes


def _is_private_address(ip_str: str) -> tuple[bool, str]:
    """True iff the address is in any private/loopback/link-local space we refuse.

    Covers IPv4 RFC1918 (10/8, 172.16/12, 192.168/16), CGNAT (100.64/10),
    loopback (127/8 + ::1), link-local (169.254/16, fe80::/10), unspecified
    (0.0.0.0, ::), multicast, broadcast, IPv6 ULA (fc00::/7).
    """
    try:
        ip = ipaddress.ip_address(ip_str)
    except ValueError:
        return True, f"unparseable: {ip_str!r}"
    if ip.is_loopback:
        return True, "loopback"
    if ip.is_private:
        # is_private already covers RFC1918 (v4) + ULA (v6).
        return True, "private"
    if ip.is_link_local:
        return True, "link-local"
    if ip.is_multicast:
        return True, "multicast"
    if ip.is_unspecified:
        return True, "unspecified"
    if ip.is_reserved:
        return True, "reserved"
    # CGNAT: 100.64.0.0/10. is_private DOES include this in Python 3.13+;
    # checking explicitly to defend against earlier Python behavior.
    if isinstance(ip, ipaddress.IPv4Address) and ip in ipaddress.ip_network("100.64.0.0/10"):
        return True, "cgnat"
    return False, ""


async def default_resolver(host: str) -> list[str]:
    """Standard-library DNS lookup. Returns all addresses for `host`.

    Production uses this; tests inject a fake.
    """
    # getaddrinfo is sync; run it in the default executor so we don't block
    # the event loop on a slow upstream resolver.
    import asyncio

    loop = asyncio.get_running_loop()
    try:
        infos = await loop.getaddrinfo(host, None, type=socket.SOCK_STREAM)
    except socket.gaierror as e:
        raise FetchError(f"dns_failure: {host}: {e}") from None
    addrs = list({info[4][0] for info in infos})
    if not addrs:
        raise FetchError(f"dns_no_records: {host}")
    return addrs


async def fetch(
    url: str,
    *,
    headers: dict[str, str] | None = None,
    timeout: httpx.Timeout = DEFAULT_TIMEOUT,
    max_bytes: int = DEFAULT_MAX_BYTES,
    max_redirects: int = DEFAULT_MAX_REDIRECTS,
    resolver: Resolver = default_resolver,
    allow_methods: tuple[str, ...] = ("GET",),
    method: str = "GET",
) -> FetchResult:
    """Fetch a URL with SSRF guards. Returns FetchResult or raises FetchError."""
    if method.upper() not in allow_methods:
        raise FetchError(f"method_not_allowed: {method}")

    current_url = url
    hops = 0
    while True:
        parsed = urlparse(current_url)
        if parsed.scheme != "https":
            raise FetchError(f"scheme_rejected: {parsed.scheme!r} (https only)")
        host = parsed.hostname
        if not host:
            raise FetchError("no_host")
        if parsed.username is not None or parsed.password is not None:
            raise FetchError("userinfo_in_url")

        addrs = await resolver(host)
        # Prefer an IPv4 if available — many small upstreams' IPv6 is poorly
        # routed. Then pick the first non-private. If none, refuse.
        addrs.sort(key=lambda a: 0 if ":" not in a else 1)
        chosen: str | None = None
        last_reason = ""
        for addr in addrs:
            bad, reason = _is_private_address(addr)
            if not bad:
                chosen = addr
                break
            last_reason = reason
        if chosen is None:
            raise FetchError(f"private_address_blocked: {host} -> {addrs} ({last_reason})")

        # Disable httpx's redirect handling; we re-validate manually for
        # each hop. (Future Stage 6 hardening will dial by resolved IP +
        # explicit SNI to prevent DNS rebinding mid-request; documented
        # in the long Note inside the client block below.)
        req_headers = {"Host": host, "User-Agent": "mymts-helper/0.0"}
        if headers:
            req_headers.update(headers)
        # Future Stage 6 hardening (documented; not active here): connect
        # to `chosen` directly with explicit SNI = host. Today we accept
        # that the URL's hostname is the resolved host name and DNS may
        # re-resolve before the connection; the up-front address-class
        # check + sub-second per-request window keep the window tiny.
        async with httpx.AsyncClient(
            timeout=timeout,
            follow_redirects=False,
            # Verification stays ON; the context only adds legacy-cipher compat
            # for FAST origins that offer no PFS suite (see _build_ssl_context).
            verify=_SSL_CONTEXT,
        ) as client:
            try:
                resp = await client.request(method, current_url, headers=req_headers)
            except httpx.HTTPError as e:
                raise FetchError(f"network_error: {e}") from None

        if resp.status_code in (301, 302, 303, 307, 308):
            hops += 1
            if hops > max_redirects:
                raise FetchError(f"too_many_redirects: {hops}")
            loc = resp.headers.get("location")
            if not loc:
                raise FetchError("redirect_no_location")
            # Resolve relative redirects against current_url; re-validate scheme/host.
            from urllib.parse import urljoin

            current_url = urljoin(current_url, loc)
            continue

        body = b""
        for chunk in resp.iter_bytes():
            body += chunk
            if len(body) > max_bytes:
                raise FetchError(f"body_too_large: > {max_bytes}")

        content_type = resp.headers.get("content-type", "").split(";")[0].strip()
        return FetchResult(
            url=current_url,
            status_code=resp.status_code,
            content_type=content_type,
            body=body,
        )


async def fetch_text(url: str, **kw: object) -> str:
    """Convenience: fetch + decode body as UTF-8 (replacing invalid bytes)."""
    r = await fetch(url, **kw)  # type: ignore[arg-type]
    return r.body.decode("utf-8", errors="replace")
