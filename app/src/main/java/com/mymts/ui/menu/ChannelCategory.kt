package com.mymts.ui.menu

/**
 * Channel taxonomy (2026-06-11) — the sections the channel picker groups by, and
 * (shared naming with the future news-genre tree) the category each channel
 * belongs to. Pure + unit-tested; mapped by slug since the `Channel` model has
 * no category field. An unmapped slug falls to [GENERAL] (honest — never hidden).
 */
object ChannelCategory {
    const val SPORTS = "Sports"
    const val US_NEWS = "US News"
    const val GLOBAL_NEWS = "Global News"
    const val BUSINESS = "Business"
    const val WEATHER = "Weather"
    const val GENERAL = "General"

    /** Section render order in the picker. */
    val ORDER: List<String> = listOf(SPORTS, US_NEWS, GLOBAL_NEWS, BUSINESS, WEATHER, GENERAL)

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

    fun of(slug: String): String = BY_SLUG[slug] ?: GENERAL

    /**
     * Group channels into sections in [ORDER], preserving the input order
     * within each section (the caller sorts live-first), and dropping empty
     * sections. Returns `(category, channels)` pairs ready to render with
     * headers. Pure — unit-tested.
     */
    fun <T> sectioned(items: List<T>, slugOf: (T) -> String): List<Pair<String, List<T>>> {
        val byCat = items.groupBy { of(slugOf(it)) }
        return ORDER.mapNotNull { cat -> byCat[cat]?.let { cat to it } }
    }
}
