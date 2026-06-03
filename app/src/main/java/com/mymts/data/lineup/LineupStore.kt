package com.mymts.data.lineup

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
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

    private val _overrides = mutableStateOf(readFromDisk())

    /**
     * Live state — recomposes any composable observing it on every
     * [assign]/[clearSlot]/[clearAll]. Stage 5's WallScreen reads this
     * and re-computes its slot list when it changes.
     */
    val overrides: State<Map<Int, String>> get() = _overrides

    fun assign(slotIndex: Int, slug: String) {
        require(slotIndex >= 0) { "slotIndex must be >= 0" }
        require(slug.isNotBlank()) { "slug must be non-blank" }
        update { it + (slotIndex to slug) }
    }

    fun clearSlot(slotIndex: Int) {
        update { it - slotIndex }
    }

    fun clearAll() {
        update { emptyMap() }
    }

    private fun update(transform: (Map<Int, String>) -> Map<Int, String>) {
        val next = transform(_overrides.value)
        _overrides.value = next
        writeToDisk(next)
    }

    private fun writeToDisk(map: Map<Int, String>) {
        val arr = JSONArray()
        map.toSortedMap().forEach { (idx, slug) ->
            arr.put(idx)
            arr.put(slug)
        }
        prefs.edit().putString(KEY_OVERRIDES, arr.toString()).apply()
    }

    private fun readFromDisk(): Map<Int, String> {
        val raw = prefs.getString(KEY_OVERRIDES, null) ?: return emptyMap()
        return try {
            decode(raw)
        } catch (e: JSONException) {
            // Corrupt blob — drop it and start fresh rather than crash.
            Log.w(TAG, "lineup_overrides parse failed, dropping: ${e.message}")
            prefs.edit().remove(KEY_OVERRIDES).apply()
            emptyMap()
        }
    }

    companion object {
        private const val PREFS_NAME = "mymts_lineup"
        private const val KEY_OVERRIDES = "lineup_overrides"
        private const val TAG = "MyMTS.LineupStore"

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
    }
}
