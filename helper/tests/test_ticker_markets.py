"""Defensive-parse + snapshot-build tests for the markets ticker.

External responses are mocked as raw bytes — the parsers must be strict
(drop malformed rows, never raise) and the snapshot builder must mix
real + sample honestly.
"""

from __future__ import annotations

from mymts_helper.ticker import DIR_DOWN, DIR_FLAT, DIR_UP
from mymts_helper.ticker import markets


# ---- Stooq CSV parser ----

STOOQ_OK = (
    b"Symbol,Date,Time,Open,High,Low,Close,Volume,Name\n"
    b"^SPX,2026-06-05,16:42:26,7535.6,7540.7,7495.6,7509.4,386183284,US LARGE\n"
    b"^DJI,2026-06-05,16:42:26,44000.0,44100.0,43900.0,44120.5,1,DOW\n"
    b"EURUSD,2026-06-05,16:42:48,1.1613,1.16442,1.15541,1.15557,,EUR/USD\n"
)


def test_stooq_parses_close_and_direction() -> None:
    out = markets.parse_stooq_csv(STOOQ_OK)
    assert out["^spx"][0] == 7509.4
    assert out["^spx"][1] == DIR_DOWN   # close < open
    assert out["^dji"][1] == DIR_UP     # close > open
    assert out["eurusd"][0] == 1.15557


def test_stooq_drops_nd_and_malformed_rows() -> None:
    body = (
        b"Symbol,Date,Time,Open,High,Low,Close,Volume,Name\n"
        b"^BAD,2026-06-05,16:00,N/D,N/D,N/D,N/D,N/D,Bad\n"   # Stooq "no data"
        b"garbage line with too few cols\n"
        b"^SPX,2026-06-05,16:00,100,1,1,105,1,SPX\n"
    )
    out = markets.parse_stooq_csv(body)
    assert "^bad" not in out
    assert out["^spx"][1] == DIR_UP
    assert len(out) == 1


def test_stooq_empty_or_header_only_is_empty() -> None:
    assert markets.parse_stooq_csv(b"") == {}
    assert markets.parse_stooq_csv(b"Symbol,Date,Open,Close\n") == {}


def test_stooq_never_raises_on_binary_junk() -> None:
    # Must not raise on non-UTF8 / random bytes.
    assert markets.parse_stooq_csv(b"\xff\xfe\x00\x01junk") == {}


def test_stooq_flat_when_open_missing() -> None:
    body = (
        b"Symbol,Date,Time,Open,High,Low,Close,Volume,Name\n"
        b"^SPX,2026-06-05,16:00,N/D,1,1,105,1,SPX\n"
    )
    out = markets.parse_stooq_csv(body)
    assert out["^spx"][1] == DIR_FLAT  # open unparseable -> flat, close still kept


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
    stooq = {"^spx": (7509.4, DIR_DOWN)}      # only S&P fetched
    coingecko = {"bitcoin": (60900.0, DIR_DOWN)}  # only BTC fetched
    entries = markets.build_snapshot(stooq, coingecko)
    by_symbol = {e.symbol: e for e in entries}

    # Fetched ones are real (pill dropped):
    assert by_symbol["S&P 500"].is_sample is False
    assert by_symbol["S&P 500"].display == "7,509.40"
    assert by_symbol["BTC"].is_sample is False
    assert by_symbol["BTC"].display == "$60,900"

    # Not-fetched ones fall back to honest sample (pill kept):
    assert by_symbol["DOW"].is_sample is True
    assert by_symbol["ETH"].is_sample is True

    # Always-sample symbols stay sample:
    assert by_symbol["Brent"].is_sample is True
    assert by_symbol["WTI"].is_sample is True
    assert by_symbol["10Y UST"].is_sample is True


def test_all_sample_snapshot_is_entirely_sample() -> None:
    entries = markets.all_sample_snapshot()
    assert entries, "should produce the full canonical list"
    assert all(e.is_sample for e in entries), "pre-poll snapshot must be all sample"
    # The full canonical set is present.
    symbols = {e.symbol for e in entries}
    for expected in ("S&P 500", "EUR/USD", "Gold", "BTC", "ETH", "Brent", "10Y UST"):
        assert expected in symbols


def test_fx_formatting_jpy_vs_majors() -> None:
    stooq = {"eurusd": (1.15557, DIR_UP), "usdjpy": (154.187, DIR_UP)}
    entries = {e.symbol: e for e in markets.build_snapshot(stooq, {})}
    assert entries["EUR/USD"].display == "1.1556"   # 4 dp
    assert entries["USD/JPY"].display == "154.19"   # 2 dp
