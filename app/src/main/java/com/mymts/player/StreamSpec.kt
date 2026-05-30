package com.mymts.player

/**
 * Minimal value object for a stream the harness should play.
 *
 * Hard boundary against WyzeGrid (Trust Bar A1, technical-approach §2.2):
 * MyMTS plays only **public web video** by URL. There is no camera model,
 * no auth, no RTSP. The url field is constrained to schemes the
 * StreamPlayer understands (http/https → HLS). Anything else is an error
 * surfaced at the boundary, never silently played.
 */
data class StreamSpec(
    val id: String,
    val label: String,
    val url: String,
) {
    init {
        require(url.startsWith("http://") || url.startsWith("https://")) {
            "MyMTS stream URLs must be http(s); got: $url"
        }
    }
}
