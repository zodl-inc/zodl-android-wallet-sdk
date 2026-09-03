package cash.z.ecc.android.sdk.model

import cash.z.ecc.android.sdk.ext.toHex

/**
 * [toString] hex-dumps [byteArray], which is appropriate for non-secret data (transaction
 * ids, raw transactions, and the like). Types that embed secret material in a
 * [FirstClassByteArray] must override [toString] themselves to redact it.
 */
class FirstClassByteArray(
    val byteArray: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as FirstClassByteArray

        return byteArray.contentEquals(other.byteArray)
    }

    override fun hashCode() = byteArray.contentHashCode()

    override fun toString(): String = "FirstClassByteArray(${byteArray.toHex()})"
}
