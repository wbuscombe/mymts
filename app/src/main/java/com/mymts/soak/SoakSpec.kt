package com.mymts.soak

/**
 * Inputs to a soak run. All knobs the operator can twist from
 * `adb shell am start ... --es ... --ei ...` come through here.
 */
data class SoakSpec(
    val tiles: Int,
    val resolutionHint: String = "auto",
    val pool: String = "live",  // "live" | "stable"
    val heartbeatIntervalMs: Long = 30_000L,
)
