"""Markets ticker data — keyless quotes from Stooq + CoinGecko.

Two keyless public sources cover most of the ticker's symbols:
  - **Stooq** serves tiny CSV quote snapshots for indices, FX, and gold
    (`https://stooq.com/q/l/?s=...&f=...&e=csv`).
  - **CoinGecko** (free, keyless) serves BTC/ETH spot + 24h change.

Symbols with no clean free keyless source (Brent, WTI, 10Y UST) are
kept as honest **sample** entries — marked `is_sample=True`, never
faked as live. This is the C3 honesty line applied per symbol: the
ticker may mix real and sample, but each is labelled truthfully.

Parsing is strict and defensive (Trust Bar A1): the CSV is split by
line/comma with every numeric field guarded by `float()` in a
try/except; a malformed row is dropped, never trusted. No `eval`, no
dynamic dispatch on upstream content.
"""

from __future__ import annotations

import json
import logging

from . import DIR_DOWN, DIR_FLAT, DIR_UP, TickerEntryDTO

log = logging.getLogger("mymts_helper.ticker.markets")

# Stooq fields: symbol, date, time, open, high, low, close, volume, name.
STOOQ_FIELDS = "sd2t2ohlcvn"

# Canonical symbol order + how each is sourced. Order mirrors the TV's
# original sample set so the marquee reads familiarly.
STOOQ_INDICES = [
    ("S&P 500", "^spx"),
    ("DOW", "^dji"),
    ("NASDAQ", "^ndq"),
    ("FTSE", "^ftm"),
    ("DAX", "^dax"),
    ("Nikkei", "^nkx"),
    ("Hang Seng", "^hsi"),
]
STOOQ_FX = [
    ("EUR/USD", "eurusd"),
    ("GBP/USD", "gbpusd"),
    ("USD/JPY", "usdjpy"),
]
STOOQ_GOLD = [
    ("Gold", "xauusd"),
]
COINGECKO_CRYPTO = [
    ("BTC", "bitcoin"),
    ("ETH", "ethereum"),
]

# Symbols with no clean free keyless source — kept honest-sample. Values
# are static placeholders; `is_sample=True` travels to the TV's SAMPLE pill.
SAMPLE_ONLY: list[TickerEntryDTO] = [
    TickerEntryDTO("Brent", "73.42", DIR_FLAT, is_sample=True),
    TickerEntryDTO("WTI", "69.15", DIR_FLAT, is_sample=True),
    TickerEntryDTO("10Y UST", "4.41%", DIR_FLAT, is_sample=True),
]

# Final-snapshot ordering: indices, FX, gold, sample commodities/rates, crypto.
# (Brent/WTI sit with the other commodities near gold; 10Y at the end.)


def stooq_url(symbols: list[str]) -> str:
    joined = "+".join(symbols)
    return f"https://stooq.com/q/l/?s={joined}&f={STOOQ_FIELDS}&h&e=csv"


COINGECKO_URL = (
    "https://api.coingecko.com/api/v3/simple/price"
    "?ids=bitcoin,ethereum&vs_currencies=usd&include_24hr_change=true"
)


def _direction(open_v: float | None, close_v: float) -> str:
    if open_v is None:
        return DIR_FLAT
    if close_v > open_v:
        return DIR_UP
    if close_v < open_v:
        return DIR_DOWN
    return DIR_FLAT


def _fmt_index(v: float) -> str:
    return f"{v:,.2f}"


def _fmt_fx(label: str, v: float) -> str:
    # JPY pairs quote ~150; show 2 dp. Other majors ~1.x; show 4 dp.
    return f"{v:,.2f}" if "JPY" in label else f"{v:.4f}"


def _fmt_crypto(v: float) -> str:
    return f"${v:,.0f}" if v >= 100 else f"${v:,.2f}"


def parse_stooq_csv(body: bytes) -> dict[str, tuple[float, str]]:
    """Parse a Stooq CSV quote snapshot → {lowercased symbol: (close, direction)}.

    Strict + defensive: header is required; rows with a non-numeric close
    are dropped; "N/D" placeholder rows (Stooq's "no data") are dropped.
    Never raises on malformed content — returns whatever parsed cleanly.
    """
    out: dict[str, tuple[float, str]] = {}
    try:
        text = body.decode("utf-8", "replace")
    except Exception:  # noqa: BLE001
        return out
    lines = [ln for ln in text.splitlines() if ln.strip()]
    if len(lines) < 2:
        return out
    header = [h.strip().lower() for h in lines[0].split(",")]
    try:
        i_sym = header.index("symbol")
        i_open = header.index("open")
        i_close = header.index("close")
    except ValueError:
        return out
    for raw in lines[1:]:
        cols = raw.split(",")
        if len(cols) <= max(i_sym, i_open, i_close):
            continue
        sym = cols[i_sym].strip().lower()
        if not sym:
            continue
        try:
            close_v = float(cols[i_close])
        except (ValueError, IndexError):
            continue  # "N/D" or junk — drop, do not trust
        try:
            open_v: float | None = float(cols[i_open])
        except (ValueError, IndexError):
            open_v = None
        out[sym] = (close_v, _direction(open_v, close_v))
    return out


def parse_coingecko(body: bytes) -> dict[str, tuple[float, str]]:
    """Parse CoinGecko simple/price JSON → {id: (usd, direction)}.

    Direction from `usd_24h_change` sign. Strict: any missing/!-numeric
    field drops that coin rather than guessing.
    """
    out: dict[str, tuple[float, str]] = {}
    try:
        data = json.loads(body)
    except (json.JSONDecodeError, ValueError):
        return out
    if not isinstance(data, dict):
        return out
    for coin_id, payload in data.items():
        if not isinstance(payload, dict):
            continue
        usd = payload.get("usd")
        if not isinstance(usd, (int, float)):
            continue
        change = payload.get("usd_24h_change")
        if isinstance(change, (int, float)):
            direction = DIR_UP if change > 0 else DIR_DOWN if change < 0 else DIR_FLAT
        else:
            direction = DIR_FLAT
        out[str(coin_id)] = (float(usd), direction)
    return out


def build_snapshot(
    stooq: dict[str, tuple[float, str]],
    coingecko: dict[str, tuple[float, str]],
) -> list[TickerEntryDTO]:
    """Assemble the full canonical markets entry list from parsed sources.

    Each canonical symbol becomes a real entry when its source returned a
    value this cycle, otherwise an honest sample placeholder (is_sample
    True). The sample-only symbols (Brent/WTI/10Y) are always sample.
    The list is always the full set so the ticker shape is stable.
    """
    entries: list[TickerEntryDTO] = []

    def add_stooq(label: str, sym: str, fmt) -> None:
        hit = stooq.get(sym.lower())
        if hit is not None:
            close_v, direction = hit
            entries.append(TickerEntryDTO(label, fmt(close_v), direction, is_sample=False))
        else:
            entries.append(_sample_for(label))

    for label, sym in STOOQ_INDICES:
        add_stooq(label, sym, _fmt_index)
    for label, sym in STOOQ_FX:
        add_stooq(label, sym, lambda v, _l=label: _fmt_fx(_l, v))
    for label, sym in STOOQ_GOLD:
        add_stooq(label, sym, _fmt_index)

    # Sample-only commodities + rates sit here, after gold.
    entries.extend(SAMPLE_ONLY)

    for label, coin_id in COINGECKO_CRYPTO:
        hit = coingecko.get(coin_id)
        if hit is not None:
            usd, direction = hit
            entries.append(TickerEntryDTO(label, _fmt_crypto(usd), direction, is_sample=False))
        else:
            entries.append(_sample_for(label))

    return entries


# Static fallback values used when a real source misses a symbol this
# cycle — plausible-shaped but always is_sample=True.
_SAMPLE_FALLBACK: dict[str, str] = {
    "S&P 500": "5,820.14", "DOW": "44,910.65", "NASDAQ": "19,772.18",
    "FTSE": "8,344.20", "DAX": "19,388.81", "Nikkei": "39,500.37",
    "Hang Seng": "20,997.93", "EUR/USD": "1.0834", "GBP/USD": "1.2671",
    "USD/JPY": "154.18", "Gold": "2,742.30", "BTC": "67,210", "ETH": "3,452",
}


def _sample_for(label: str) -> TickerEntryDTO:
    return TickerEntryDTO(label, _SAMPLE_FALLBACK.get(label, "—"), DIR_FLAT, is_sample=True)


def all_sample_snapshot() -> list[TickerEntryDTO]:
    """The full markets list, every entry sample. Used before the first
    successful poll and in phantom mode."""
    return build_snapshot(stooq={}, coingecko={})
