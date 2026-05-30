"""Log redaction tests.

The redaction middleware is meant to be paranoid. These tests document what
it catches — if redaction stops catching one of these patterns, that's a
regression worth a security review.
"""

from __future__ import annotations

from mymts_helper.log import redact


def test_bearer_token_redacted() -> None:
    out = redact("Authorization: Bearer abc123XYZ.token-value")
    assert "abc123XYZ" not in out
    assert "REDACTED" in out


def test_authorization_header_redacted() -> None:
    out = redact("Got header Authorization: Basic dXNlcjpwYXNz")
    assert "dXNlcjpwYXNz" not in out


def test_api_key_header_redacted() -> None:
    out = redact("X-API-Key: sk_live_supersecret")
    assert "supersecret" not in out


def test_internal_ipv4_redacted() -> None:
    for ip in ("10.0.0.1", "<LAN_IP>", "172.20.10.5", "127.0.0.1", "169.254.1.1"):
        assert ip not in redact(f"connecting to {ip}:443"), ip


def test_public_ipv4_not_redacted() -> None:
    # Public addresses are operationally useful in logs (e.g., upstream API hosts).
    # Redaction must not blanket-strip every dotted-quad it sees.
    assert "8.8.8.8" in redact("resolved upstream 8.8.8.8")
