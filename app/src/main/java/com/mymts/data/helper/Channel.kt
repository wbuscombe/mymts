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
