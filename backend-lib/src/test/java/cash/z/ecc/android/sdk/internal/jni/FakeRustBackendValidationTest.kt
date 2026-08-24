package cash.z.ecc.android.sdk.internal.jni

import cash.z.ecc.android.sdk.internal.model.JniRewindResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/**
 * Exercises the [Backend][cash.z.ecc.android.sdk.internal.Backend] boundary argument validation
 * against [FakeRustBackend], which is constructible without the native library.
 */
class FakeRustBackendValidationTest {
    private fun newBackend() = FakeRustBackend(networkId = 0, metadata = mutableListOf())

    @Test
    fun negative_height_throws() =
        runTest {
            val backend = newBackend()

            assertFailsWith<IllegalArgumentException> { backend.rewindToHeight(-1L) }
        }

    @Test
    fun max_uint_height_is_accepted() =
        runTest {
            val backend = newBackend()

            val result = backend.rewindToHeight(0xFFFF_FFFFL)

            assertIs<JniRewindResult.Success>(result)
        }

    @Test
    fun above_uint_range_height_throws() =
        runTest {
            val backend = newBackend()

            assertFailsWith<IllegalArgumentException> { backend.rewindToHeight(0x1_0000_0000L) }
        }

    @Test
    fun negative_index_throws() =
        runTest {
            val backend = newBackend()

            assertFailsWith<IllegalArgumentException> {
                backend.putUtxo(
                    txId = ByteArray(32),
                    index = -1,
                    script = ByteArray(0),
                    value = 0L,
                    height = 0L
                )
            }
        }
}
