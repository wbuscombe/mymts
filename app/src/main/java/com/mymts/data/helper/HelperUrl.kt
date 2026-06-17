package com.mymts.data.helper

/**
 * Pure helper-URL resolution + normalization (no Android deps — unit-tested).
 *
 * The app resolves its helper base URL through a precedence chain, first valid wins:
 *   1. a user-entered, persisted RUNTIME value ([HelperUrlStore]) — added 2026-06 so
 *      a STOCK APK can be pointed at any helper with **no rebuild**;
 *   2. an adb intent-extra `helper` override (power-user / debugging — kept);
 *   3. the compile-time `BuildConfig.HELPER_BASE_URL` — but ONLY when it was actually
 *      configured at build time (`BuildConfig.HELPER_URL_CONFIGURED`), so the operator's
 *      own build + dev workflow resolve out-of-box, while a stock APK (whose default is
 *      just the `http://localhost:8091` demo fallback) falls through to first-run setup.
 *
 * [resolve] returns null when nothing valid resolves → the app shows its first-run setup.
 */
object HelperUrl {

    private val SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")

    /**
     * Normalize a user/host input into a base URL the client can use, or null if it
     * can't be a helper address. Accepts `host`, `host:port`, or a full
     * `scheme://host[:port][/path]`; tolerates surrounding whitespace + trailing
     * slashes. A schemeless input defaults to `http://` (the common LAN case) — we
     * never silently force https; the user types `https://…` explicitly when their
     * deployment needs it.
     */
    fun normalize(input: String?): String? {
        val raw = input?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val withScheme = if (SCHEME.containsMatchIn(raw)) raw else "http://$raw"
        val trimmed = withScheme.trimEnd('/')
        val afterScheme = trimmed.substringAfter("://", "")
        if (afterScheme.isEmpty()) return null
        val authority = afterScheme.substringBefore('/').substringBefore('?')
        val host = authority.substringBefore(':')
        if (host.isBlank() || host.any { it.isWhitespace() }) return null
        val port = authority.substringAfter(':', "")
        if (port.isNotEmpty() && (port.toIntOrNull() == null || port.toInt() !in 1..65535)) return null
        return trimmed
    }

    /**
     * Resolve the base URL through the precedence chain. [buildConfigured] guards the
     * BuildConfig default — a stock APK's localhost fallback is NOT a resolution, so an
     * unconfigured build falls through to setup. Returns the first valid normalized URL,
     * or null → first-run setup.
     */
    fun resolve(
        persisted: String?,
        adbExtra: String?,
        buildDefault: String?,
        buildConfigured: Boolean,
    ): String? = normalize(persisted)
        ?: normalize(adbExtra)
        ?: if (buildConfigured) normalize(buildDefault) else null
}
