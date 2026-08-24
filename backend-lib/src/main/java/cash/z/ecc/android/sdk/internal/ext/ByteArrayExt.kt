@file:Suppress("ktlint:standard:filename")

package cash.z.ecc.android.sdk.internal.ext

/**
 * Overwrites this array with zeros. Best effort only: the JVM may hold unreachable
 * copies (GC compaction, JIT) that cannot be cleared from here.
 */
fun ByteArray.clearContents() {
    fill(0)
}

/**
 * Runs [block] with this array and zeroes it afterwards, including when [block]
 * throws. The array must not be retained past [block]'s return.
 */
inline fun <R> ByteArray.useAndClear(block: (ByteArray) -> R): R =
    try {
        block(this)
    } finally {
        fill(0)
    }
