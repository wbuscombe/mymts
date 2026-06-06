// Pure render/label/grouping logic for the MyMTS LAN web client.
//
// This module holds the *honesty-bearing* logic — the same C3 discipline
// the native app enforces — as pure functions with NO DOM and NO fetch,
// so it is unit-tested with `node --test` (no browser, no toolchain).
// The browser loads it as an ES module; `app.js` calls these to turn
// helper-API JSON into display strings/structures, and only ever writes
// them into the DOM as text.
//
// A1 (closed door): every function here returns plain strings/data the
// caller renders as text. Nothing here builds HTML, resolves a URL, or
// touches an article page. The web client is a dumb consumer of the
// helper's already-inert plain-text data, exactly like the native app.

// ----- direction glyphs (markets) / none (sports) -----

export function directionGlyph(direction) {
  switch (direction) {
    case "up": return "▲";    // ▲
    case "down": return "▼";  // ▼
    case "flat": return "■";  // ■
    case "none": return "";        // sports: no arrow
    default: return "";            // unknown → no glyph (forward-compatible)
  }
}

export function directionClass(direction) {
  if (direction === "up") return "dir-up";
  if (direction === "down") return "dir-down";
  return "dir-flat";
}

// ----- ticker entry → display model (honest sample/stale) -----

/**
 * Turn a ticker envelope + entry into a display row model.
 * `isSample` and the envelope `stale` flag travel through untouched —
 * the caller renders a SAMPLE pill / stale note, never upgrades sample
 * to live. Returns { symbol, value, glyph, dirClass, sample }.
 */
export function tickerRow(entry) {
  return {
    symbol: String(entry.symbol ?? ""),
    value: String(entry.display ?? ""),
    glyph: directionGlyph(entry.direction),
    dirClass: directionClass(entry.direction),
    sample: entry.is_sample === true,
  };
}

/**
 * Envelope-level staleness note for a ticker mode. Honest: an all-sample
 * pre-poll snapshot (as_of null) is NOT stale — sample is honest, not
 * stale. Returns "" when fresh/honest-sample, else a short note.
 */
export function tickerStaleNote(envelope) {
  if (!envelope) return "";
  if (envelope.stale === true) return "stale — not updating";
  return "";
}

// ----- feed source filter (browser-local view pref, mirrors the wall) -----

/**
 * Drop items whose source is in the hidden-set (case-insensitive). A
 * DENYLIST — new sources show by default, same semantics as the native
 * wall's hiddenSources. Pure; the web settings persist the set in
 * localStorage (a view pref, not the TV's setting — the web client can't
 * write the TV's on-device settings).
 */
export function filterHiddenSources(items, hiddenSet) {
  if (!hiddenSet || hiddenSet.size === 0) return items ?? [];
  const lower = new Set([...hiddenSet].map((s) => String(s).toLowerCase()));
  return (items ?? []).filter((it) => {
    const key = (it.source && it.source.trim()) ? it.source : "Unknown source";
    return !lower.has(key.toLowerCase());
  });
}

/** Playable channels only (live + has a URL) — for the in-browser grid. */
export function playableChannels(channels) {
  return (channels ?? []).filter((c) => channelStatus(c).playable);
}

// ----- feed: group by source, newest-first (mirrors FeedListBuilder) -----

/**
 * Group feed items into source sections, alphabetical (case-insensitive)
 * by source, newest-first within each section. Pure — same ordering the
 * native FeedListBuilder produces, so the two clients read the same.
 * Returns [{ source, items: [...] }].
 */
export function groupBySource(items) {
  const bySource = new Map();
  for (const it of items ?? []) {
    const key = (it.source && it.source.trim()) ? it.source : "Unknown source";
    if (!bySource.has(key)) bySource.set(key, []);
    bySource.get(key).push(it);
  }
  const sources = [...bySource.keys()].sort((a, b) =>
    a.toLowerCase().localeCompare(b.toLowerCase()));
  return sources.map((source) => ({
    source,
    items: bySource.get(source).slice().sort(byNewestFirst),
  }));
}

function itemTimestamp(it) {
  // Prefer published, fall back to fetched; ISO-8601 sorts lexically as time.
  return (it.published_at && it.published_at.trim()) ||
         (it.fetched_at && it.fetched_at.trim()) || "";
}

function byNewestFirst(a, b) {
  const ta = itemTimestamp(a), tb = itemTimestamp(b);
  if (ta === tb) return (b.id ?? 0) - (a.id ?? 0);
  return ta < tb ? 1 : -1;  // descending (newest first)
}

// ----- relative time (10-ft readable, mirrors native RelativeTime) -----

export function relativeTime(iso, now = Date.now()) {
  if (!iso || !String(iso).trim()) return "";
  const t = Date.parse(iso);
  if (Number.isNaN(t)) return "";
  const deltaSec = Math.floor((now - t) / 1000);
  if (deltaSec < 60) return "now";              // includes small clock skew
  if (deltaSec < 3600) return `${Math.floor(deltaSec / 60)}m`;
  if (deltaSec < 86400) return `${Math.floor(deltaSec / 3600)}h`;
  if (deltaSec < 30 * 86400) return `${Math.floor(deltaSec / 86400)}d`;
  return String(iso).slice(0, 10);              // YYYY-MM-DD
}

// ----- channel status (honest live/offline) -----

/**
 * Honest channel status label. A channel is "live" only if the helper
 * says status=live AND it carries a current_url; anything else is
 * offline/unknown — never shown as playable. Mirrors Channel.isPlayable.
 * Returns { label, playable }.
 */
export function channelStatus(channel) {
  const playable = channel.status === "live" && !!(channel.current_url && String(channel.current_url).trim());
  let label;
  if (playable) {
    label = "live";
  } else if (channel.status === "unknown" || channel.status == null || channel.status === "") {
    // Prober hasn't classified it yet — honestly "unknown", not "offline".
    label = "unknown";
  } else {
    // Known-not-playable: status=unavailable, OR the contradictory
    // status=live-without-url. Either way it won't play → "offline".
    label = "offline";
  }
  return { label, playable };
}

// ----- honest empty / unreachable states -----

/**
 * Feed empty-state text. Never a blank pane that looks broken.
 * fetchOk=false means the helper was unreachable on the last poll.
 */
export function feedEmptyState({ itemCount, stale, fetchOk }) {
  if (itemCount > 0) return "";
  if (!fetchOk) return "Helper unreachable — feed paused.";
  if (stale) return "Feed not updating — sources may be stale.";
  return "Waiting for the first feed sweep…";
}

/**
 * Whether the whole view should show a helper-unreachable banner.
 * Honest: distinguishes "no data yet" from "helper down".
 */
export function helperUnreachable(lastFetchOk, everSucceeded) {
  return everSucceeded === true && lastFetchOk === false;
}
