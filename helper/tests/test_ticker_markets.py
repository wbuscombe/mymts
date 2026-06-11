"""Defensive-parse + snapshot-build tests for the markets ticker.

External responses are mocked as raw bytes — the parsers must be strict
(drop malformed/missing fields, never raise) and the snapshot builder must
mix real + sample honestly. Source: Yahoo Finance v8 chart (indices/FX/gold/
oil/10Y) + CoinGecko (crypto).
"""

from __future__ import annotations

import json

from mymts_helper.ticker import DIR_DOWN, DIR_FLAT, DIR_UP
from mymts_helper.ticker import markets


# ---- Yahoo v8 chart parser ----

def _chart(price, prev=None, *, key="chartPreviousClose") -> bytes:
    meta = {"currency": "USD", "symbol": "^GSPC", "regularMarketPrice": price}
    if prev is not None:
        meta[key] = prev
    return json.dumps({"chart": {"result": [{"meta": meta}], "error": None}}).encode()


def test_yahoo_parses_price_and_direction() -> None:
    assert markets.parse_yahoo_chart(_chart(7394.3, 7266.99)) == (7394.3, DIR_UP)
    assert markets.parse_yahoo_chart(_chart(100.0, 120.0)) == (100.0, DIR_DOWN)
    assert markets.parse_yahoo_chart(_chart(50.0, 50.0)) == (50.0, DIR_FLAT)


def test_yahoo_falls_back_to_previousClose() -> None:
    # No chartPreviousClose, but previousClose present → still gets a direction.
    out = markets.parse_yahoo_chart(_chart(10.0, 9.0, key="previousClose"))
    assert out == (10.0, DIR_UP)


def test_yahoo_flat_when_no_prev() -> None:
    out = markets.parse_yahoo_chart(_chart(10.0))  # no prev close at all
    assert out == (10.0, DIR_FLAT)


def test_yahoo_drops_missing_or_nonnumeric_price() -> None:
    assert markets.parse_yahoo_chart(_chart(None)) is None  # null price
    bad = json.dumps({"chart": {"result": [{"meta": {"regularMarketPrice": "x"}}]}}).encode()
    assert markets.parse_yahoo_chart(bad) is None
    # bool must not be accepted as a number
    boolprice = json.dumps({"chart": {"result": [{"meta": {"regularMarketPrice": True}}]}}).encode()
    assert markets.parse_yahoo_chart(boolprice) is None


def test_yahoo_never_raises_on_junk() -> None:
    assert markets.parse_yahoo_chart(b"not json") is None
    assert markets.parse_yahoo_chart(b"\xff\xfe\x00\x01") is None
    assert markets.parse_yahoo_chart(b'{"chart":{"result":[]}}') is None  # empty result
    assert markets.parse_yahoo_chart(b'{"chart":{"error":"boom"}}') is None
    assert markets.parse_yahoo_chart(b'[1,2,3]') is None  # not the expected shape


# ---- CoinGecko JSON parser ----

def test_coingecko_parses_price_and_direction() -> None:
    body = (
        b'{"bitcoin":{"usd":60900,"usd_24h_change":-4.74},'
        b'"ethereum":{"usd":1614.14,"usd_24h_change":8.9}}'
    )
    out = markets.parse_coingecko(body)
    assert out["bitcoin"] == (60900.0, DIR_DOWN)
    assert out["ethereum"][0] == 1614.14
    assert out["ethereum"][1] == DIR_UP


def test_coingecko_drops_missing_usd() -> None:
    body = b'{"bitcoin":{"usd_24h_change":1.0},"ethereum":{"usd":1600}}'
    out = markets.parse_coingecko(body)
    assert "bitcoin" not in out
    assert out["ethereum"][1] == DIR_FLAT  # no change field -> flat


def test_coingecko_never_raises_on_junk() -> None:
    assert markets.parse_coingecko(b"not json") == {}
    assert markets.parse_coingecko(b"[1,2,3]") == {}  # not a dict


# ---- snapshot builder (real + sample mixing) ----

def test_build_snapshot_marks_real_vs_sample_honestly() -> None:
    # Yahoo dict is keyed by the Yahoo symbol. Only S&P + Brent fetched.
    yahoo = {"^GSPC": (7509.4, DIR_DOWN), "BZ=F": (89.09, DIR_DOWN)}
    coingecko = {"bitcoin": (60900.0, DIR_DOWN)}  # only BTC fetched
    entries = markets.build_snapshot(yahoo, coingecko)
    by_symbol = {e.symbol: e for e in entries}

    # Fetched ones are real (pill dropped):
    assert by_symbol["S&P 500"].is_sample is False
    assert by_symbol["S&P 500"].display == "7,509.40"
    assert by_symbol["Brent"].is_sample is False     # oil now LIVE, not sample-only
    assert by_symbol["Brent"].display == "89.09"
    assert by_symbol["BTC"].is_sample is False
    assert by_symbol["BTC"].display == "$60,900"

    # Not-fetched ones fall back to honest sample (pill kept):
    assert by_symbol["DOW"].is_sample is True
    assert by_symbol["WTI"].is_sample is True
    assert by_symbol["10Y UST"].is_sample is True
    assert by_symbol["ETH"].is_sample is True


def test_all_sample_snapshot_is_entirely_sample() -> None:
    entries = markets.all_sample_snapshot()
    assert entries, "should produce the full canonical list"
    assert all(e.is_sample for e in entries), "pre-poll snapshot must be all sample"
    symbols = {e.symbol for e in entries}
    for expected in ("S&P 500", "EUR/USD", "Gold", "BTC", "ETH", "Brent", "WTI", "10Y UST"):
        assert expected in symbols


def test_fx_formatting_jpy_vs_majors() -> None:
    yahoo = {"EURUSD=X": (1.15557, DIR_UP), "USDJPY=X": (154.187, DIR_UP)}
    entries = {e.symbol: e for e in markets.build_snapshot(yahoo, {})}
    assert entries["EUR/USD"].display == "1.1556"   # 4 dp
    assert entries["USD/JPY"].display == "154.19"   # 2 dp


def test_rate_and_index_formatting() -> None:
    yahoo = {"^TNX": (4.463, DIR_DOWN), "^GSPC": (7394.3, DIR_UP)}
    entries = {e.symbol: e for e in markets.build_snapshot(yahoo, {})}
    assert entries["10Y UST"].display == "4.46%"    # yield as percent
    assert entries["S&P 500"].display == "7,394.30"  # grouped index


def test_yahoo_chart_url_quotes_special_symbols() -> None:
    assert markets.yahoo_chart_url("^GSPC").endswith("/chart/%5EGSPC?interval=1d&range=1d")
    assert "%3D" in markets.yahoo_chart_url("GC=F")  # '=' is path-encoded
