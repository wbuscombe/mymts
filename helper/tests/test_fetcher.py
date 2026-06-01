"""Tests for the SSRF-safe fetcher.

The point of these tests is to prove the fetcher rejects every address class
we care about WITHOUT ever opening a socket. We do that by injecting a
fake resolver — the fetcher should bail before httpx is even consulted.
"""

from __future__ import annotations

import pytest

from mymts_helper.fetcher import FetchError, _is_private_address, fetch


@pytest.mark.parametrize(
    "ip,expected_bad",
    [
        # IPv4 RFC 1918
        ("10.0.0.1", True),
        ("10.255.255.255", True),
        ("172.16.0.1", True),
        ("172.31.255.254", True),
        ("192.168.0.1", True),
        ("<LAN_IP>", True),
        # IPv4 loopback / link-local / unspecified
        ("127.0.0.1", True),
        ("169.254.1.1", True),
        ("0.0.0.0", True),
        # IPv4 CGNAT
        ("100.64.0.1", True),
        ("100.127.255.254", True),
        # IPv4 TEST-NET (reserved — never legitimate destination)
        ("203.0.113.1", True),
        ("198.18.0.1", True),
        # IPv6 loopback / ULA / link-local / unspecified
        ("::1", True),
        ("fc00::1", True),
        ("fd00::1", True),
        ("fe80::1", True),
        ("::", True),
        # Garbage
        ("not-an-ip", True),
        ("", True),
        # Public addresses pass
        ("8.8.8.8", False),
        ("1.1.1.1", False),
        ("2606:4700::1111", False),
    ],
)
def test_private_address_classifier(ip: str, expected_bad: bool) -> None:
    """Only the bad/not-bad bit is contractual. The `reason` string is
    informational — Python's ipaddress.is_private overlaps with several
    other categories at the implementation level (link-local, reserved,
    etc.), so pinning the exact reason would lock us to the std-lib's
    current categorisation."""
    bad, _ = _is_private_address(ip)
    if expected_bad:
        assert bad, f"{ip} should be classified bad"
    else:
        assert not bad, f"{ip} should be classified ok"


async def _fake_resolver_factory(addrs: list[str]):
    async def _r(host: str) -> list[str]:
        return list(addrs)
    return _r


async def test_rejects_http_scheme() -> None:
    with pytest.raises(FetchError, match="scheme_rejected"):
        await fetch("http://example.test/feed", resolver=await _fake_resolver_factory(["8.8.8.8"]))


async def test_rejects_userinfo_in_url() -> None:
    with pytest.raises(FetchError, match="userinfo_in_url"):
        await fetch(
            "https://user:pass@example.test/feed",
            resolver=await _fake_resolver_factory(["8.8.8.8"]),
        )


async def test_rejects_when_resolver_returns_private_ipv4() -> None:
    with pytest.raises(FetchError, match="private_address_blocked"):
        await fetch(
            "https://example.test/feed",
            resolver=await _fake_resolver_factory(["<LAN_IP>"]),
        )


async def test_rejects_when_resolver_returns_loopback() -> None:
    with pytest.raises(FetchError, match="private_address_blocked"):
        await fetch(
            "https://example.test/feed",
            resolver=await _fake_resolver_factory(["127.0.0.1"]),
        )


async def test_rejects_when_resolver_returns_link_local() -> None:
    with pytest.raises(FetchError, match="private_address_blocked"):
        await fetch(
            "https://example.test/feed",
            resolver=await _fake_resolver_factory(["169.254.169.254"]),
        )


async def test_rejects_when_all_returned_addresses_are_private() -> None:
    # Even if a host resolves to a mix, all-private means no usable address.
    with pytest.raises(FetchError, match="private_address_blocked"):
        await fetch(
            "https://example.test/feed",
            resolver=await _fake_resolver_factory(["10.0.0.1", "fe80::1", "192.168.1.1"]),
        )


async def test_method_rejected_for_disallowed() -> None:
    with pytest.raises(FetchError, match="method_not_allowed"):
        await fetch(
            "https://example.test/feed",
            method="DELETE",
            resolver=await _fake_resolver_factory(["8.8.8.8"]),
        )


async def test_rejects_unparseable_resolver_output() -> None:
    with pytest.raises(FetchError, match="private_address_blocked"):
        await fetch(
            "https://example.test/feed",
            resolver=await _fake_resolver_factory(["this-is-not-an-ip"]),
        )
