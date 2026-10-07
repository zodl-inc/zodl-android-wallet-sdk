package com.zodl.slipstream.internal

import android.app.ActivityManager
import android.content.Context

/**
 * The memory hint `engine.open` passes to the engine for a synchronizer that may use [fraction] of
 * the device's [deviceTotalMemoryBytes]. The engine reads the hint as the device's RAM and uses it
 * for one decision only: below its small-device threshold (3 GiB) it replaces its default fetch and
 * split budgets with fixed, smaller ones. A fraction below 1 therefore gives a helper wallet running
 * beside the main one those smaller budgets on devices where the main wallet still gets the defaults
 * (below 6 GiB for `0.5`), and the same default budgets as the main wallet on larger devices; never
 * larger budgets than the main wallet's, as the result never exceeds [deviceTotalMemoryBytes]. An
 * unknown device size (`0` or less) stays unknown (`0`), which the engine treats as "use the
 * defaults".
 *
 * @throws IllegalArgumentException if [fraction] is not in `(0, 1]`.
 */
internal fun engineMemoryHint(
    deviceTotalMemoryBytes: Long,
    fraction: Float
): Long {
    require(fraction > 0f && fraction <= 1f) { "The engine memory fraction must be in (0, 1]" }
    if (deviceTotalMemoryBytes <= 0L) return 0L
    return (deviceTotalMemoryBytes * fraction.toDouble()).toLong().coerceIn(1L, deviceTotalMemoryBytes)
}

/**
 * [engineMemoryHint] for this device, whose total RAM [ActivityManager.getMemoryInfo] reports:
 * what `SlipstreamSynchronizer.new` hands its engine as `totalMemoryBytes` for [fraction].
 *
 * @throws IllegalArgumentException if [fraction] is not in `(0, 1]`.
 */
internal fun engineMemoryBytes(
    context: Context,
    fraction: Float
): Long {
    val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val memoryInfo = ActivityManager.MemoryInfo().also { activityManager.getMemoryInfo(it) }
    return engineMemoryHint(memoryInfo.totalMem, fraction)
}
