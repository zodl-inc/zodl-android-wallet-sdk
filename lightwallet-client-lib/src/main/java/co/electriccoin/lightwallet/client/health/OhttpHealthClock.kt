package co.electriccoin.lightwallet.client.health

/**
 * The monotonic clock of all health evidence, in nanoseconds.
 *
 * On Android `System.nanoTime()` reads `CLOCK_MONOTONIC`, the same clock the Slipstream engine
 * stamps its observations with (checked on a device: the two differ by tens of microseconds).
 * Do not use `SystemClock.elapsedRealtime*` (`CLOCK_BOOTTIME`) or wall time to compare with it.
 */
object OhttpHealthClock {
    fun nowNanos(): Long = System.nanoTime()
}
