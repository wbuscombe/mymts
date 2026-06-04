"""Stage 6 / TLS — Config.from_env + Config.has_https tests."""

from __future__ import annotations

import pytest

from mymts_helper.config import Config


def _set_env(monkeypatch: pytest.MonkeyPatch, **values: str) -> None:
    for k, v in values.items():
        monkeypatch.setenv(k, v)


def test_v1_behaviour_https_fields_default_to_none(monkeypatch: pytest.MonkeyPatch) -> None:
    # When no TLS env is set, has_https() is False and the helper
    # behaves exactly as before — HTTP-only on PORT.
    monkeypatch.delenv("HTTPS_PORT", raising=False)
    monkeypatch.delenv("SSL_KEYFILE", raising=False)
    monkeypatch.delenv("SSL_CERTFILE", raising=False)
    cfg = Config.from_env()
    assert cfg.https_port is None
    assert cfg.ssl_keyfile is None
    assert cfg.ssl_certfile is None
    assert cfg.has_https() is False


def test_full_tls_config_enables_https(monkeypatch: pytest.MonkeyPatch) -> None:
    _set_env(
        monkeypatch,
        HTTPS_PORT="8443",
        SSL_KEYFILE="/etc/ssl/mymts/helper.key",
        SSL_CERTFILE="/etc/ssl/mymts/helper.crt",
    )
    cfg = Config.from_env()
    assert cfg.https_port == 8443
    assert cfg.ssl_keyfile == "/etc/ssl/mymts/helper.key"
    assert cfg.ssl_certfile == "/etc/ssl/mymts/helper.crt"
    assert cfg.has_https() is True


def test_has_https_requires_all_three(monkeypatch: pytest.MonkeyPatch) -> None:
    # Half-configured TLS must NOT silently degrade — the entry point
    # treats `has_https()` as the only "enabled" signal, so partial
    # config means HTTP-only behaviour, which is honest about the
    # operator's intent.
    _set_env(monkeypatch, HTTPS_PORT="8443")
    monkeypatch.delenv("SSL_KEYFILE", raising=False)
    monkeypatch.delenv("SSL_CERTFILE", raising=False)
    assert Config.from_env().has_https() is False

    _set_env(monkeypatch, SSL_KEYFILE="/etc/ssl/mymts/helper.key")
    monkeypatch.delenv("SSL_CERTFILE", raising=False)
    assert Config.from_env().has_https() is False

    _set_env(monkeypatch, SSL_CERTFILE="/etc/ssl/mymts/helper.crt")
    assert Config.from_env().has_https() is True


def test_empty_env_strings_treated_as_unset(monkeypatch: pytest.MonkeyPatch) -> None:
    # Docker compose sometimes sets vars to "" when interpolating
    # missing values — treat blanks the same as unset rather than
    # falling into int() parsing and crashing.
    _set_env(monkeypatch, HTTPS_PORT="", SSL_KEYFILE="", SSL_CERTFILE="")
    cfg = Config.from_env()
    assert cfg.https_port is None
    assert cfg.ssl_keyfile is None
    assert cfg.ssl_certfile is None
    assert cfg.has_https() is False
