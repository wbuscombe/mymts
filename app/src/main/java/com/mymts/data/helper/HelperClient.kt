package com.mymts.data.helper

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal HTTP client for the helper's pinned JSON endpoints.
 *
 * Stage 3 only consumes two endpoints (`/api/channels`, `/api/feed`),
 * so we use the platform's [HttpURLConnection] + [JSONObject] rather
 * than pulling OkHttp/Moshi/kotlinx-serialization for this one screen.
 * Adding a network/serializer stack is a Stage 5+ call, not Stage 3's.
 *
 * Discipline:
 *   - tight timeouts (connect 4 s, read 6 s): a slow helper must not
 *     wedge the wall — Trust Bar C2 (no cascade failures).
 *   - schema_version pinning: refuse unknown versions outright. C3.
 *   - parsing failures surface as [HelperException], not silent empty
 *     state — the repository converts those into a STALE indicator.
 */
class HelperClient(
    private val baseUrl: String,
    private val connectTimeoutMs: Int = 4_000,
    private val readTimeoutMs: Int = 6_000,
) {
    sealed class Result<out T> {
        data class Ok<T>(val value: T) : Result<T>()
        data class Err(val cause: Throwable) : Result<Nothing>()
    }

    /** Fetch and parse `/api/channels`. Wraps any failure into [Result.Err]. */
    suspend fun fetchChannels(): Result<ChannelsSnapshot> = request("/api/channels") { json ->
        parseChannels(json)
    }

    /**
     * Fetch and parse `/api/feed` with a bounded item limit. The helper
     * already strips HTML — we render whatever it returns as native text.
     */
    suspend fun fetchFeed(limit: Int = 100): Result<FeedSnapshot> =
        request("/api/feed?limit=$limit") { json -> parseFeed(json) }

    private suspend fun <T> request(path: String, parse: (JSONObject) -> T): Result<T> = try {
        val body = openGet(path)
        val obj = JSONObject(body)
        Result.Ok(parse(obj))
    } catch (e: Throwable) {
        Log.w(TAG, "GET $path failed: ${e.javaClass.simpleName}: ${e.message}")
        Result.Err(e)
    }

    private suspend fun openGet(path: String): String = withContext(Dispatchers.IO) {
        val conn = (URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "MyMTS-wall/1")
            doInput = true
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IOException("$path -> HTTP $code")
            }
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val TAG = "MyMTS.HelperClient"
        const val SUPPORTED_SCHEMA_VERSION = 1

        internal fun parseStatus(raw: String): Channel.Status = when (raw.lowercase()) {
            "live" -> Channel.Status.LIVE
            "unavailable" -> Channel.Status.UNAVAILABLE
            else -> Channel.Status.UNKNOWN
        }

        /**
         * Parse a `/api/feed` JSON body. Items without a `title` are skipped
         * (the helper guarantees titles, but defending against the contract
         * drifting is cheap).
         */
        fun parseFeed(json: JSONObject): FeedSnapshot {
            val version = json.optInt("schema_version", -1)
            if (version != SUPPORTED_SCHEMA_VERSION) {
                throw HelperException("feed schema_version=$version not supported")
            }
            val arr = json.optJSONArray("items")
                ?: throw HelperException("items field missing")
            val list = (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val title = o.optStringOrNull("title") ?: return@mapNotNull null
                FeedItem(
                    id = o.optLong("id", -1L),
                    source = o.optStringOrNull("source") ?: "",
                    title = title,
                    summary = o.optStringOrNull("summary"),
                    link = o.optStringOrNull("link"),
                    publishedAtIso = o.optStringOrNull("published_at"),
                    fetchedAtIso = o.optStringOrNull("fetched_at"),
                )
            }
            return FeedSnapshot(version, list)
        }

        /**
         * Parse a `/api/channels` JSON body. Exposed for unit tests so we
         * can validate the schema contract without a live HTTP connection.
         */
        fun parseChannels(json: JSONObject): ChannelsSnapshot {
            val version = json.optInt("schema_version", -1)
            if (version != SUPPORTED_SCHEMA_VERSION) {
                throw HelperException("channels schema_version=$version not supported")
            }
            val arr = json.optJSONArray("channels")
                ?: throw HelperException("channels field missing")
            val list = (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Channel(
                    slug = o.requireString("slug"),
                    label = o.optString("label", o.requireString("slug")),
                    kind = o.optString("kind", "hls"),
                    currentUrl = o.optStringOrNull("current_url"),
                    status = parseStatus(o.optString("status", "")),
                    lastSuccessAt = o.optStringOrNull("last_success_at"),
                    lastError = o.optStringOrNull("last_error"),
                    errorCount = o.optInt("error_count", 0),
                )
            }
            return ChannelsSnapshot(version, list)
        }
    }
}

class HelperException(message: String) : Exception(message)

private fun JSONObject.requireString(key: String): String =
    if (has(key) && !isNull(key)) getString(key)
    else throw HelperException("required field '$key' missing")

private fun JSONObject.optStringOrNull(key: String): String? {
    if (!has(key) || isNull(key)) return null
    val v = optString(key, "")
    return v.takeIf { it.isNotBlank() }
}
