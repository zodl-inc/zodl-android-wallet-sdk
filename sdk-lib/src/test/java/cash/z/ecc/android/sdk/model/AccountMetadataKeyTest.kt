package cash.z.ecc.android.sdk.model

import cash.z.ecc.android.sdk.internal.model.JniMetadataKey
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNotSame

class AccountMetadataKeyTest {
    @Test
    fun toUnsafe_returns_fresh_arrays_each_call() {
        val key =
            AccountMetadataKey(
                JniMetadataKey(
                    sk = ByteArray(32) { 1 },
                    chainCode = ByteArray(32) { 2 }
                )
            )

        val first = key.toUnsafe()
        val second = key.toUnsafe()

        assertNotSame(first.sk, second.sk)
        assertNotSame(first.chainCode, second.chainCode)
    }

    @Test
    fun zeroing_one_toUnsafe_copy_does_not_affect_the_next() {
        val key =
            AccountMetadataKey(
                JniMetadataKey(
                    sk = ByteArray(32) { 1 },
                    chainCode = ByteArray(32) { 2 }
                )
            )

        val first = key.toUnsafe()
        first.sk.fill(0)
        first.chainCode.fill(0)

        val second = key.toUnsafe()

        assertContentEquals(ByteArray(32) { 1 }, second.sk)
        assertContentEquals(ByteArray(32) { 2 }, second.chainCode)
    }
}
