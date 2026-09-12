package com.mymts.data.helper

/**
 * One row from the helper's `/api/channels` response.
 *
 * Trust Bar **C3 boundary**: the helper masks `current_url` to `null`
 * whenever `status != "live"`. The TV must respect that — a non-live
 * channel has *no* playable URL. We model that at the type level by
 * keeping [currentUrl] nullable; the wall code asks [isPlayable] rather
 * than inspecting fields by hand.
 */
data class Channel(
    val slug: String,
    val label: String,
    val kind: String,
    /**
     * The picker section this channel belongs to, **assigned by the helper**
     * (`/api/channels` → `category`). The helper is authoritative, so a channel
     * added server-side groups correctly with no app rebuild. May be blank only
     * against an older helper that doesn't serve it — the picker then falls back
     * to its compiled `ChannelCategory.of(slug)` map. See [ChannelCategory].
     * Defaults blank for non-picker constructors; the parser always sets it.
     */
    val category: String = "",
    val currentUrl: String?,
    val status: Status,
    val lastSuccessAt: String?,
    val lastError: String?,
    val errorCount: Int,
) {
    enum class Status { LIVE, UNAVAILABLE, UNKNOWN }

    /** A channel is playable iff the helper says it's live AND gave us a URL. */
    val isPlayable: Boolean
        get() = status == Status.LIVE && !currentUrl.isNullOrBlank()

    /**
     * A non-video WIDGET source (unified registry, 2026-07). The helper lists these
     * on `/api/channels` for EVERY surface (no per-surface gate); [kind] tells the
     * wall to render an animated image (Coil) instead of a `<video>`/ExoPlayer stream,
     * and a widget carries no audio/caption track (its per-tile toggles are hidden).
     * Currently the NWS weather-radar loops; a future widget kind joins [WIDGET_KINDS].
     * Mirrors the helper's `weather.regions.is_widget_kind`.
     */
    val isWidget: Boolean get() = kind in WIDGET_KINDS

    /** The weather-radar widget specifically (an NWS RIDGE loop image). */
    val isRadar: Boolean get() = kind == KIND_WEATHER_RADAR

    companion object {
        /** Wire `kind` for the NWS weather-radar widget (mirrors helper RADAR_KIND). */
        const val KIND_WEATHER_RADAR = "weather-radar"

        /** Every non-video widget kind. Radar is the first; future widgets join here. */
        val WIDGET_KINDS = setOf(KIND_WEATHER_RADAR)

        /** Slug prefix the helper assigns radar pseudo-channels (mirrors helper
         *  RADAR_SLUG_PREFIX + web RADAR_SLUG_PREFIX). Used by the lineup selector to
         *  keep radar OUT of the auto-filled default grid (it is an explicit per-cell
         *  pick only), parity with the web client. */
        const val RADAR_SLUG_PREFIX = "weather-radar-"

        /** True iff [slug] is a radar pseudo-channel slug (e.g. weather-radar-kilx). */
        fun isRadarSlug(slug: String): Boolean = slug.startsWith(RADAR_SLUG_PREFIX)
    }
}

/**
 * Parsed `/api/channels` snapshot. [schemaVersion] is pinned at the
 * helper boundary (currently `1`); the wall refuses unknown versions
 * rather than guessing at field semantics.
 */
data class ChannelsSnapshot(
    val schemaVersion: Int,
    val channels: List<Channel>,
) {
    /** Only the channels we can actually play right now. */
    val playable: List<Channel> get() = channels.filter { it.isPlayable }
}

/**
 * A server-authoritative wall preset (`/api/presets`) — a switchable channel-set
 * the user applies to the grid. The helper defines them; the picker renders
 * whatever is served (a new preset needs no app rebuild). [fill] is `"topup"` (the
 * News default: preferred, then the rest) or `"exact"` (only [slugs], curated).
 * [gridRows]/[gridCols] are the suggested grid (nullable).
 *
 * Since MYMTS-014 the default wall builds its lineup from the FULL channel set
 * ([com.mymts.ui.wall.LineupSelector.defaultWallLineup]), not the playable subset:
 * a configured channel that is down keeps its original default-wall slot and renders
 * the existing Offline tile, and live channels do not shift up or repeat into it.
 */
data class Preset(
    val id: String,
    val name: String,
    val slugs: List<String>,
    val fill: String,
    val gridRows: Int?,
    val gridCols: Int?,
)

/** Parsed `/api/presets` snapshot. [default] is the no-op preset id (`"news"`). */
data class PresetsSnapshot(
    val schemaVersion: Int,
    val default: String,
    val presets: List<Preset>,
)

/**
 * Parsed `/health` response — used by the runtime helper-URL setup/Settings to
 * confirm a candidate address is actually a MyMTS helper (a `buildSha` is present),
 * not merely a server that answered HTTP 200.
 */
data class HealthInfo(
    val buildSha: String,
    val schemaVersion: Int,
)

/**
 * One row from the helper's `/api/feed` response. The helper guarantees
 * inert plain text (HTML stripped at parse time) — the TV renders
 * everything as native Text and never instantiates a WebView.
 *
 * `title` is always present; `summary` and `link` may be null. The
 * timestamp pair gives the publisher time when present, falling back
 * to the helper's fetch time.
 */
data class FeedItem(
    val id: Long,
    val source: String,
    val title: String,
    val summary: String?,
    val link: String?,
    val publishedAtIso: String?,
    val fetchedAtIso: String?,
)

/**
 * Parsed `/api/feed` snapshot.
 */
data class FeedSnapshot(
    val schemaVersion: Int,
    val items: List<FeedItem>,
)
