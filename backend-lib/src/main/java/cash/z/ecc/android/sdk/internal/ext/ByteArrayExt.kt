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
 * throws.
 *
 * [block] must not retain the array past its return - no assigning it to a field, closing
 * over it in a callback, handing it to another coroutine, or returning it as (part of) [R].
 * This array is zeroed unconditionally once [block] returns or throws, so any alias [block]
 * kept escapes with its contents silently wiped out from under it. There is no runtime check
 * for this: the no-aliasing contract is convention-only, enforced by caller discipline, not by
 * this function.
 */
inline fun <R> ByteArray.useAndClear(block: (ByteArray) -> R): R =
    try {
        block(this)
    } finally {
        fill(0)
    }
