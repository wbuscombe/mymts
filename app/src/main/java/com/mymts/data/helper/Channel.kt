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
