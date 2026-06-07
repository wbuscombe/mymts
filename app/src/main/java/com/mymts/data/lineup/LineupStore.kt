package com.mymts.data.lineup

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import com.mymts.data.settings.FeedFontScale
import com.mymts.data.settings.FeedSide
import com.mymts.data.settings.FeedWidth
import com.mymts.data.settings.FeedRecency
import com.mymts.data.settings.Overscan
import com.mymts.data.settings.UiScale
import com.mymts.data.settings.WallSettings
import com.mymts.data.settings.feedRecencyFromOrdinal
import com.mymts.data.settings.feedFontScaleFromOrdinal
import com.mymts.data.settings.feedSideFromOrdinal
import com.mymts.data.settings.feedWidthFromOrdinal
import com.mymts.data.settings.overscanFromOrdinal
import com.mymts.data.settings.uiScaleFromOrdinal
import org.json.JSONArray
import org.json.JSONException

/**
 * On-device persistence for the operator's per-slot channel lineup.
 *
 * Storage shape (SharedPreferences key `lineup_overrides`):
 * a JSON array `[slotIndex, slug, slotIndex, slug, …]` where each pair
 * (i, s) means "slot i is operator-assigned to channel slug s." A
 * missing slot (i.e. an index not in the array) means "no override —
 * use the default lineup selector for that slot." Slots are stored as
 * pairs rather than an object so the format stays compact and stable
 * regardless of the tile count.
 *
 * Why SharedPreferences and not DataStore: the lineup is at most a
 * handful of small strings, written rarely (when the operator picks a
 * channel) and read once at boot. SharedPreferences is the lightweight
 * Android idiom for exactly this shape and is already on the
 * classpath; adding the DataStore dependency for one tiny preference
 * doesn't pay back.
 *
 * **Trust Bar invariants this store honors:**
 *   - **No secrets / no absolute paths.** The values stored are
 *     short slug strings (e.g. `cbs-sports-hq`); nothing identifying
 *     the operator, nothing PII.
 *   - **Honest fallback.** If a saved slug no longer matches any
 *     helper channel, the wall's [com.mymts.ui.wall.TileSlotResolver]
 *     falls through to the default cycler for that slot — the
 *     operator never sees a stale label for a vanished channel.
 *   - **No logging of slug content** — slugs are non-sensitive but
 *     consistency with the existing log-redaction discipline says
 *     not to add new payload to logs.
 */
class LineupStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _overrides = mutableStateOf(readOverridesFromDisk())
    private val _audibleSlot = mutableIntStateOf(readAudibleSlotFromDisk())
    private val _captionsOnSlots = mutableStateOf(readCaptionsFromDisk())
    private val _wallSettings = mutableStateOf(readWallSettingsFromDisk())

    /**
     * Live state — recomposes any composable observing it on every
     * [assign]/[clearSlot]/[clearAll]. Stage 5's WallScreen reads this
     * and re-computes its slot list when it changes.
     */
    val overrides: State<Map<Int, String>> get() = _overrides

    /**
     * The slot index currently audible (single-audible-tile model), or
     * `-1` if every tile is muted. The default is `-1` — the wall starts
     * muted; only an explicit operator "make audible" via the controls
     * overlay puts a slot here.
     */
    val audibleSlot: State<Int> get() = _audibleSlot

    /**
     * Slot indices where the operator has toggled captions ON. Soft
     * caption tracks are off by default for every slot ([com.mymts.player.StreamPlayer]
     * disables `C.TRACK_TYPE_TEXT` at startup); inclusion here re-enables
     * the text track for that slot. Burned-in captions are pixels in the
     * video and unaffected by this set — see the per-channel caption table.
     */
    val captionsOnSlots: State<Set<Int>> get() = _captionsOnSlots

    /**
     * The operator's wall layout settings — feed width, feed font
     * scale, which side the feed lives on. Persisted alongside the
     * lineup; restored on app launch with safe defaults if missing or
     * corrupt. Live state recomposes any composable observing it on
     * every [updateWallSettings].
     */
    val wallSettings: State<WallSettings> get() = _wallSettings

    fun updateWallSettings(settings: WallSettings) {
        _wallSettings.value = settings
        prefs.edit()
            .putInt(KEY_FEED_WIDTH, settings.feedWidth.ordinal)
            .putInt(KEY_FEED_FONT, settings.feedFontScale.ordinal)
            .putInt(KEY_FEED_SIDE, settings.feedSide.ordinal)
            .putString(KEY_FEED_HIDDEN_SOURCES, encodeStringSet(settings.hiddenSources))
            .putInt(KEY_FEED_RECENCY, settings.feedRecency.ordinal)
            .putString(KEY_HIDDEN_LEAGUES, encodeStringSet(settings.hiddenLeagues))
            .putBoolean(KEY_TICKER_NEWS, settings.tickerNewsEnabled)
            .putInt(KEY_UI_SCALE, settings.uiScale.ordinal)
            .putInt(KEY_OVERSCAN, settings.overscan.ordinal)
            .apply()
    }

    /** Cycle the global UI scale (Compact → Default → Roomy → Compact). */
    fun cycleUiScale() {
        val next = UiScale.values().let { it[(_wallSettings.value.uiScale.ordinal + 1) % it.size] }
        updateWallSettings(_wallSettings.value.copy(uiScale = next))
    }

    /** Cycle the overscan-safe inset (None → 3% → 5% → 7% → None). */
    fun cycleOverscan() {
        val next = Overscan.values().let { it[(_wallSettings.value.overscan.ordinal + 1) % it.size] }
        updateWallSettings(_wallSettings.value.copy(overscan = next))
    }

    /** Toggle a sports league's visibility in the ticker (denylist). */
    fun toggleHiddenLeague(league: String) {
        val current = _wallSettings.value.hiddenLeagues
        val next = if (league in current) current - league else current + league
        updateWallSettings(_wallSettings.value.copy(hiddenLeagues = next))
    }

    /** Toggle news as a third ticker rotation mode (default off). */
    fun toggleTickerNews() {
        updateWallSettings(_wallSettings.value.copy(tickerNewsEnabled = !_wallSettings.value.tickerNewsEnabled))
    }

    /** Advance the recency window (All → 1h → 6h → 24h → All). */
    fun cycleFeedRecency() {
        val next = FeedRecency.values().let { it[(_wallSettings.value.feedRecency.ordinal + 1) % it.size] }
        updateWallSettings(_wallSettings.value.copy(feedRecency = next))
    }

    /**
     * Toggle a source's visibility in the feed. Stored as a denylist
     * (`hiddenSources`), so toggling a visible source OFF adds it; an
     * already-hidden source ON removes it. New sources never appear here
     * until explicitly hidden, so they show by default.
     */
    fun toggleHiddenSource(source: String) {
        val current = _wallSettings.value.hiddenSources
        val next = if (source in current) current - source else current + source
        updateWallSettings(_wallSettings.value.copy(hiddenSources = next))
    }

    fun cycleFeedWidth() {
        val next = FeedWidth.values().let { it[(_wallSettings.value.feedWidth.ordinal + 1) % it.size] }
        updateWallSettings(_wallSettings.value.copy(feedWidth = next))
    }

    fun cycleFeedFontScale() {
        val next = FeedFontScale.values().let { it[(_wallSettings.value.feedFontScale.ordinal + 1) % it.size] }
        updateWallSettings(_wallSettings.value.copy(feedFontScale = next))
    }

    fun cycleFeedSide() {
        val next = FeedSide.values().let { it[(_wallSettings.value.feedSide.ordinal + 1) % it.size] }
        updateWallSettings(_wallSettings.value.copy(feedSide = next))
    }

    fun assign(slotIndex: Int, slug: String) {
        require(slotIndex >= 0) { "slotIndex must be >= 0" }
        require(slug.isNotBlank()) { "slug must be non-blank" }
        updateOverrides { it + (slotIndex to slug) }
    }

    fun clearSlot(slotIndex: Int) {
        updateOverrides { it - slotIndex }
    }

    fun clearAll() {
        updateOverrides { emptyMap() }
    }

    /**
     * Make [slotIndex] the audible tile. If it was already audible,
     * mutes the wall (toggle semantics). The single-audible-tile model
     * means selecting a tile to unmute mutes every other tile — the
     * operator never has to manually mute the prior one.
     */
    fun toggleAudible(slotIndex: Int) {
        require(slotIndex >= 0) { "slotIndex must be >= 0" }
        val next = if (_audibleSlot.intValue == slotIndex) -1 else slotIndex
        _audibleSlot.intValue = next
        prefs.edit().putInt(KEY_AUDIBLE_SLOT, next).apply()
    }

    fun muteAll() {
        if (_audibleSlot.intValue == -1) return
        _audibleSlot.intValue = -1
        prefs.edit().putInt(KEY_AUDIBLE_SLOT, -1).apply()
    }

    /**
     * Toggle captions for [slotIndex]. The result is the new state
     * (true = captions now on, false = off). The actual effect depends
     * on whether the stream carries a soft text track — see
     * [com.mymts.player.StreamPlayer.setCaptionsEnabled]; the
     * [SlotControlsOverlay] surfaces honest "captions not available"
     * when the stream has no track to toggle.
     */
    fun toggleCaptions(slotIndex: Int): Boolean {
        require(slotIndex >= 0) { "slotIndex must be >= 0" }
        val current = _captionsOnSlots.value
        val next = if (slotIndex in current) current - slotIndex else current + slotIndex
        _captionsOnSlots.value = next
        prefs.edit().putString(KEY_CAPTIONS_ON, encodeIntSet(next)).apply()
        return slotIndex in next
    }

    private fun updateOverrides(transform: (Map<Int, String>) -> Map<Int, String>) {
        val next = transform(_overrides.value)
        _overrides.value = next
        writeOverridesToDisk(next)
    }

    private fun writeOverridesToDisk(map: Map<Int, String>) {
        prefs.edit().putString(KEY_OVERRIDES, encode(map)).apply()
    }

    private fun readOverridesFromDisk(): Map<Int, String> {
        val raw = prefs.getString(KEY_OVERRIDES, null) ?: return emptyMap()
        return try {
            decode(raw)
        } catch (e: JSONException) {
            Log.w(TAG, "lineup_overrides parse failed, dropping: ${e.message}")
            prefs.edit().remove(KEY_OVERRIDES).apply()
            emptyMap()
        }
    }

    private fun readAudibleSlotFromDisk(): Int =
        prefs.getInt(KEY_AUDIBLE_SLOT, -1)

    // Delegates to the PURE [resolveWallSettings] (companion) so the
    // key-wiring + defaults are unit-testable without an Android context.
    private fun readWallSettingsFromDisk(): WallSettings = resolveWallSettings(
        contains = prefs::contains,
        getInt = prefs::getInt,
        getStringSet = ::readStringSet,
        getBoolean = prefs::getBoolean,
    )

    private fun readStringSet(key: String): Set<String> {
        val raw = prefs.getString(key, null) ?: return emptySet()
        return try {
            decodeStringSet(raw)
        } catch (e: JSONException) {
            Log.w(TAG, "$key parse failed, dropping: ${e.message}")
            prefs.edit().remove(key).apply()
            emptySet()
        }
    }

    private fun readCaptionsFromDisk(): Set<Int> {
        val raw = prefs.getString(KEY_CAPTIONS_ON, null) ?: return emptySet()
        return try {
            decodeIntSet(raw)
        } catch (e: JSONException) {
            Log.w(TAG, "captions_on parse failed, dropping: ${e.message}")
            prefs.edit().remove(KEY_CAPTIONS_ON).apply()
            emptySet()
        }
    }

    companion object {
        private const val PREFS_NAME = "mymts_lineup"
        private const val KEY_OVERRIDES = "lineup_overrides"
        private const val KEY_AUDIBLE_SLOT = "audible_slot"
        private const val KEY_CAPTIONS_ON = "captions_on_slots"
        private const val KEY_FEED_WIDTH = "wall_settings_feed_width"
        private const val KEY_FEED_FONT = "wall_settings_feed_font"
        private const val KEY_FEED_SIDE = "wall_settings_feed_side"
        private const val KEY_FEED_HIDDEN_SOURCES = "wall_settings_feed_hidden_sources"
        private const val KEY_FEED_RECENCY = "wall_settings_feed_recency"
        private const val KEY_HIDDEN_LEAGUES = "wall_settings_hidden_leagues"
        private const val KEY_TICKER_NEWS = "wall_settings_ticker_news"
        private const val KEY_UI_SCALE = "wall_settings_ui_scale"
        private const val KEY_OVERSCAN = "wall_settings_overscan"
        private const val TAG = "MyMTS.LineupStore"

        /**
         * Pure resolver for the persisted wall settings — accessor lambdas
         * stand in for SharedPreferences so the key-wiring + per-field
         * defaults are unit-testable without an Android context. Each field
         * falls back to its own default if absent/out-of-range (partial
         * corruption doesn't discard the others); ALL keys absent → the full
         * [WallSettings.Default]. `overscan` defaults to the TV-safe Medium
         * (not None), so an existing install gains the safe inset on first
         * run after this update without losing its other prefs.
         */
        internal fun resolveWallSettings(
            contains: (String) -> Boolean,
            getInt: (String, Int) -> Int,
            getStringSet: (String) -> Set<String>,
            getBoolean: (String, Boolean) -> Boolean,
        ): WallSettings {
            if (!contains(KEY_FEED_WIDTH) && !contains(KEY_FEED_FONT) &&
                !contains(KEY_FEED_SIDE) && !contains(KEY_FEED_HIDDEN_SOURCES) &&
                !contains(KEY_FEED_RECENCY) && !contains(KEY_HIDDEN_LEAGUES) &&
                !contains(KEY_TICKER_NEWS) && !contains(KEY_UI_SCALE) &&
                !contains(KEY_OVERSCAN)
            ) {
                return WallSettings.Default
            }
            return WallSettings(
                feedWidth = feedWidthFromOrdinal(getInt(KEY_FEED_WIDTH, FeedWidth.Default.ordinal)),
                feedFontScale = feedFontScaleFromOrdinal(getInt(KEY_FEED_FONT, FeedFontScale.Default.ordinal)),
                feedSide = feedSideFromOrdinal(getInt(KEY_FEED_SIDE, FeedSide.Left.ordinal)),
                hiddenSources = getStringSet(KEY_FEED_HIDDEN_SOURCES),
                feedRecency = feedRecencyFromOrdinal(getInt(KEY_FEED_RECENCY, FeedRecency.All.ordinal)),
                hiddenLeagues = getStringSet(KEY_HIDDEN_LEAGUES),
                tickerNewsEnabled = getBoolean(KEY_TICKER_NEWS, false),
                uiScale = uiScaleFromOrdinal(getInt(KEY_UI_SCALE, UiScale.Default.ordinal)),
                overscan = overscanFromOrdinal(getInt(KEY_OVERSCAN, Overscan.Medium.ordinal)),
            )
        }

        /**
         * Pure JSON decoder — kept package-internal + testable without
         * a real Android context. The encoder lives in [writeToDisk]
         * but uses the same `[idx, slug, idx, slug, …]` shape.
         */
        internal fun decode(raw: String): Map<Int, String> {
            val arr = JSONArray(raw)
            if (arr.length() % 2 != 0) return emptyMap()
            val out = mutableMapOf<Int, String>()
            var i = 0
            while (i < arr.length()) {
                val idx = arr.optInt(i, -1)
                val slug = arr.optString(i + 1, "")
                if (idx >= 0 && slug.isNotBlank()) {
                    out[idx] = slug
                }
                i += 2
            }
            return out.toMap()
        }

        /** Encoder mirror — used for tests. */
        internal fun encode(map: Map<Int, String>): String {
            val arr = JSONArray()
            map.toSortedMap().forEach { (idx, slug) ->
                arr.put(idx)
                arr.put(slug)
            }
            return arr.toString()
        }

        /** JSON array of integer slot indices, sorted for determinism. */
        internal fun encodeIntSet(set: Set<Int>): String {
            val arr = JSONArray()
            set.sorted().forEach { arr.put(it) }
            return arr.toString()
        }

        internal fun decodeIntSet(raw: String): Set<Int> {
            val arr = JSONArray(raw)
            val out = mutableSetOf<Int>()
            for (i in 0 until arr.length()) {
                val v = arr.optInt(i, -1)
                if (v >= 0) out.add(v)
            }
            return out.toSet()
        }

        /** JSON array of source labels (the feed-filter denylist), sorted for determinism. */
        internal fun encodeStringSet(set: Set<String>): String {
            val arr = JSONArray()
            set.sorted().forEach { arr.put(it) }
            return arr.toString()
        }

        internal fun decodeStringSet(raw: String): Set<String> {
            val arr = JSONArray(raw)
            val out = mutableSetOf<String>()
            for (i in 0 until arr.length()) {
                val v = arr.optString(i, "")
                if (v.isNotBlank()) out.add(v)
            }
            return out.toSet()
        }
    }
}
