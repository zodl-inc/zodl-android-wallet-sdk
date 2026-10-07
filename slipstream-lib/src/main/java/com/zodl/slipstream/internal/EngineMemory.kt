package com.zodl.slipstream.internal

/**
 * The memory hint `engine.open` passes to the engine for a synchronizer that may use [fraction] of
 * the device's [deviceTotalMemoryBytes]. The engine reads the hint as the device's RAM and derates
 * its fetch and split budgets for a device below its small-device threshold, so a fraction below 1
 * gives a helper wallet running beside the main one the smaller budget earlier than the main wallet
 * gets it, and never a larger one: the result never exceeds [deviceTotalMemoryBytes]. An unknown
 * device size (`0` or less) stays unknown (`0`), which the engine treats as "use the defaults".
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
