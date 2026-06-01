package com.mymts.soak

import com.mymts.player.StreamSpec

/**
 * Inputs to a soak run. All knobs the operator can twist from
 * `adb shell am start ... --es ... --ei ...` come through here.
 *
 * `adhocFixtures` lets the host-side fixture-validator launch a single
 * arbitrary stream without rebuilding the APK. When non-null it overrides
 * the named pool, and `tiles` is implicitly the size of the list.
 */
data class SoakSpec(
    val tiles: Int,
    val resolutionHint: String = "auto",
    val pool: String = "live",  // "live" | "stable" | "adhoc" (auto when adhocFixtures != null)
    val heartbeatIntervalMs: Long = 30_000L,
    val adhocFixtures: List<StreamSpec>? = null,
)
