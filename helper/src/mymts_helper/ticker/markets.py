"""Markets ticker data — keyless quotes from Yahoo Finance + CoinGecko.

Two keyless public sources cover every symbol the ticker shows:
  - **Yahoo Finance** v8 chart endpoint (`query1.finance.yahoo.com/v8/
    finance/chart/<symbol>`) serves a small JSON snapshot per symbol — its
    `meta` carries `regularMarketPrice` + `chartPreviousClose`. One request
    per symbol (per-symbol isolation: one symbol failing only samples that
    symbol). Covers indices, FX, gold, oil (Brent/WTI), and the 10Y yield.
  - **CoinGecko** (free, keyless) serves BTC/ETH spot + 24h change.

History (2026-06-11): this used to fetch **Stooq** CSV for indices/FX/gold,
but Stooq bot-walls the NAS egress IP (the prober gets a challenge page, not
data) so those symbols fell back to honest **sample**. Yahoo's chart endpoint
IS reachable from the NAS egress (verified from inside the helper container —
the WeatherNation lesson: the NAS prober is the gate, not the dev machine), so
the whole markets set now goes live; the previously sample-only commodities/
rate (Brent, WTI, 10Y UST) are live too.

Honesty (Trust Bar C3): each entry carries `is_sample`. A symbol whose source
returned a value this cycle is real (no pill); a symbol whose source missed
falls back to an honest **sample** placeholder (pill kept) — never faked live.

Parsing is strict and defensive (Trust Bar A1): every numeric field is guarded;
malformed/missing fields drop that symbol rather than guessing. No `eval`, no
dynamic dispatch on upstream content.
"""

from __future__ import annotations

import json
import logging
import math
import urllib.parse

from . import DIR_DOWN, DIR_FLAT, DIR_UP, TickerEntryDTO

log = logging.getLogger("mymts_helper.ticker.markets")

# Canonical quote set sourced from Yahoo: (label, Yahoo symbol, format key).
# Order mirrors the TV's original set so the marquee reads familiarly:
# indices, FX, gold, oil, the 10Y yield — then crypto (CoinGecko) appended.
YAHOO_QUOTES: list[tuple[str, str, str]] = [
    ("S&P 500", "^GSPC", "index"),
    ("DOW", "^DJI", "index"),
    ("NASDAQ", "^IXIC", "index"),
    ("FTSE", "^FTSE", "index"),
    ("DAX", "^GDAXI", "index"),
    ("Nikkei", "^N225", "index"),
    ("Hang Seng", "^HSI", "index"),
    ("EUR/USD", "EURUSD=X", "fx"),
    ("GBP/USD", "GBPUSD=X", "fx"),
    ("USD/JPY", "USDJPY=X", "fx"),
    ("Gold", "GC=F", "index"),
    ("Brent", "BZ=F", "price2"),
    ("WTI", "CL=F", "price2"),
    ("10Y UST", "^TNX", "rate"),
]
COINGECKO_CRYPTO = [
    ("BTC", "bitcoin"),
    ("ETH", "ethereum"),
]


def yahoo_chart_url(symbol: str) -> str:
    """Per-symbol Yahoo v8 chart URL. `^GSPC`/`GC=F`/`EURUSD=X` are
    path-quoted (the `^`, `=` become `%5E`/`%3D` — Yahoo accepts both)."""
    sym = urllib.parse.quote(symbol)
    return f"https://query1.finance.yahoo.com/v8/finance/chart/{sym}?interval=1d&range=1d"


COINGECKO_URL = (
    "https://api.coingecko.com/api/v3/simple/price"
    "?ids=bitcoin,ethereum&vs_currencies=usd&include_24hr_change=true"
)


def _direction(prev: float | None, current: float) -> str:
    if prev is None:
        return DIR_FLAT
    if current > prev:
        return DIR_UP
    if current < prev:
        return DIR_DOWN
    return DIR_FLAT


def _fmt_index(v: float) -> str:
    return f"{v:,.2f}"


def _fmt_fx(label: str, v: float) -> str:
    # JPY pairs quote ~150; show 2 dp. Other majors ~1.x; show 4 dp.
    return f"{v:,.2f}" if "JPY" in label else f"{v:.4f}"


def _fmt_price2(v: float) -> str:
    # Oil ($/bbl) and similar — two decimals, thousands-grouped.
    return f"{v:,.2f}"


def _fmt_rate(v: float) -> str:
    # A yield, shown as a percent (^TNX already quotes the percentage value).
    return f"{v:.2f}%"


def _fmt_crypto(v: float) -> str:
    return f"${v:,.0f}" if v >= 100 else f"${v:,.2f}"


_FORMATTERS = {
    "index": lambda label, v: _fmt_index(v),
    "fx": lambda label, v: _fmt_fx(label, v),
    "price2": lambda label, v: _fmt_price2(v),
    "rate": lambda label, v: _fmt_rate(v),
}


def parse_yahoo_chart(body: bytes) -> tuple[float, str] | None:
    """Parse ONE Yahoo v8 chart response → (price, direction), or None.

    Strict: the price comes from `meta.regularMarketPrice`; direction from
    `regularMarketPrice` vs `chartPreviousClose` (falling back to
    `previousClose`). Any missing/non-numeric price drops the symbol (returns
    None) rather than guessing. Never raises on malformed/binary content.
    """
    try:
        data = json.loads(body)
    except (json.JSONDecodeError, ValueError, TypeError):
        return None
    try:
        meta = data["chart"]["result"][0]["meta"]
    except (KeyError, IndexError, TypeError):
        return None
    if not isinstance(meta, dict):
        return None
    price = meta.get("regularMarketPrice")
    # json.loads accepts NaN/Infinity, and isinstance(nan, float) is True — so
    # finiteness is part of the strict-parse contract, not just type. A non-finite
    # price drops the symbol to the honest sample fallback rather than shipping a
    # fake-live 'nan'/'inf' cell (review DATA-1).
    if (
        not isinstance(price, (int, float))
        or isinstance(price, bool)
        or not math.isfinite(price)
    ):
        return None
    prev = meta.get("chartPreviousClose")
    if not isinstance(prev, (int, float)) or isinstance(prev, bool):
        prev = meta.get("previousClose")
    prev_v = (
        float(prev)
        if isinstance(prev, (int, float)) and not isinstance(prev, bool) and math.isfinite(prev)
        else None
    )
    return (float(price), _direction(prev_v, float(price)))


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
        # reject NaN/Inf as well as non-numeric (DATA-1)
        if not isinstance(usd, (int, float)) or not math.isfinite(usd):
            continue
        change = payload.get("usd_24h_change")
        if isinstance(change, (int, float)):
            direction = DIR_UP if change > 0 else DIR_DOWN if change < 0 else DIR_FLAT
        else:
            direction = DIR_FLAT
        out[str(coin_id)] = (float(usd), direction)
    return out


def build_snapshot(
    yahoo: dict[str, tuple[float, str]],
    coingecko: dict[str, tuple[float, str]],
) -> list[TickerEntryDTO]:
    """Assemble the full canonical markets entry list from parsed sources.

    `yahoo` is keyed by the Yahoo symbol (e.g. `^GSPC`, `EURUSD=X`). Each
    canonical symbol becomes a real entry when its source returned a value this
    cycle, otherwise an honest sample placeholder (`is_sample=True`). The list
    is always the full set so the ticker shape is stable.
    """
    entries: list[TickerEntryDTO] = []

    for label, sym, fmt_key in YAHOO_QUOTES:
        hit = yahoo.get(sym)
        if hit is not None:
            price, direction = hit
            entries.append(TickerEntryDTO(label, _FORMATTERS[fmt_key](label, price), direction, is_sample=False))
        else:
            entries.append(_sample_for(label))

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
    "USD/JPY": "154.18", "Gold": "2,742.30", "Brent": "73.42", "WTI": "69.15",
    "10Y UST": "4.41%", "BTC": "67,210", "ETH": "3,452",
}


def _sample_for(label: str) -> TickerEntryDTO:
    return TickerEntryDTO(label, _SAMPLE_FALLBACK.get(label, "—"), DIR_FLAT, is_sample=True)


def all_sample_snapshot() -> list[TickerEntryDTO]:
    """The full markets list, every entry sample. Used before the first
    successful poll and in phantom mode."""
    return build_snapshot(yahoo={}, coingecko={})
