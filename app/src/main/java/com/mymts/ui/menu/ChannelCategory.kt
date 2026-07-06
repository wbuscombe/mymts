package com.mymts.ui.menu

/**
 * Channel taxonomy — the sections the channel picker groups by.
 *
 * **Server-authoritative (2026-06-18):** the picker groups by the `category` the
 * helper assigns each channel (`/api/channels` → `Channel.category`), via
 * [sectionedByCategory]. The helper is the authority, so a channel added
 * server-side groups correctly with **no app rebuild** — closing the drift where
 * channels missing from the compiled map landed in General. The compiled [BY_SLUG]
 * map / [of] survive only as a *fallback* for an older helper that doesn't serve a
 * category. [ORDER] still drives section render order; an unrecognized server
 * category (e.g. a future "Government" section) is shown rather than hidden. Pure +
 * unit-tested. An unmapped slug (in the fallback) falls to [GENERAL] — never hidden.
 */
object ChannelCategory {
    const val SPORTS = "Sports"
    const val US_NEWS = "US News"
    const val GLOBAL_NEWS = "Global News"
    const val BUSINESS = "Business"
    const val WEATHER = "Weather"
    // The 2026-06 server-first sections (previously appended alphabetically by
    // [sectionedByCategory]) are now compiled into [ORDER] so the native picker
    // orders sections IDENTICALLY to the helper (channels/category.py CATEGORY_ORDER)
    // and the web (render.mjs CHANNEL_CATEGORY_ORDER) — the cross-surface parity lock.
    // WEATHER_RADAR is the widget section the native app now renders (unified registry,
    // 2026-07); the ambient trio (Cameras/Nature/Space) + Government were already
    // server-served and are made explicit here so all three surfaces agree on order.
    const val WEATHER_RADAR = "Weather Radar"
    const val CAMERAS = "Cameras"
    const val GOVERNMENT = "Government"
    const val NATURE = "Nature"
    const val SPACE = "Space"
    const val GENERAL = "General"

    /** Section render order in the picker — IDENTICAL to the helper CATEGORY_ORDER and
     *  the web CHANNEL_CATEGORY_ORDER (asserted by scripts/check_channel_parity.py). */
    val ORDER: List<String> = listOf(
        SPORTS, US_NEWS, GLOBAL_NEWS, BUSINESS, WEATHER, WEATHER_RADAR,
        CAMERAS, GOVERNMENT, NATURE, SPACE, GENERAL,
    )

    private val BY_SLUG: Map<String, String> = mapOf(
        "cbs-sports-hq" to SPORTS,
        "cnn" to US_NEWS,
        "livenow-fox" to US_NEWS,
        "newsmax" to US_NEWS,
        "c-span" to US_NEWS,
        "white-house-tv" to US_NEWS,
        "bbc-news" to GLOBAL_NEWS,
        "al-jazeera-en" to GLOBAL_NEWS,
        "dw-news-en" to GLOBAL_NEWS,
        "france24-en" to GLOBAL_NEWS,
        "cnn-international" to GLOBAL_NEWS,
        "sky-news" to GLOBAL_NEWS,
        "cgtn-en" to GLOBAL_NEWS,
        "trt-world" to GLOBAL_NEWS,
        "bloomberg-tv" to BUSINESS,
        "cnbc" to BUSINESS,
        "fox-weather" to WEATHER,
        "accuweather-now" to WEATHER,
        // redbull-tv, nasa-tv, iss-feed → GENERAL (the fallback)
    )

    /** Fallback only: the compiled slug→section map for an older helper that
     *  doesn't serve a `category`. The primary path is the server category. */
    fun of(slug: String): String = BY_SLUG[slug] ?: GENERAL

    /**
     * Group channels into sections by their **server-provided category** — the
     * durable path. [categoryOf] yields each item's section (callers pass the
     * helper's `Channel.category`, falling back to [of] only when it's blank).
     * Known sections render in [ORDER]; any category the app doesn't recognize
     * (e.g. a future server-side "Government" section) is appended just before
     * [GENERAL] so it still shows rather than vanishing. Empty sections are
     * dropped and input order is preserved within each (the caller sorts
     * live-first). Returns `(category, channels)` pairs ready to render with
     * headers. Pure — unit-tested.
     */
    fun <T> sectionedByCategory(
        items: List<T>,
        categoryOf: (T) -> String,
    ): List<Pair<String, List<T>>> {
        val byCat = items.groupBy { categoryOf(it).ifBlank { GENERAL } }
        val known = ORDER.filterNot { it == GENERAL }
        val unknown = byCat.keys.filterNot { it in ORDER }.sorted()
        return (known + unknown + GENERAL).mapNotNull { cat -> byCat[cat]?.let { cat to it } }
    }
}
