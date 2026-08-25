package cash.z.ecc.android.sdk.internal.jni

import cash.z.ecc.android.sdk.internal.model.JniRewindResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

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

    @Test
    fun negative_put_utxo_height_throws() =
        runTest {
            val backend = newBackend()

            assertFailsWith<IllegalArgumentException> {
                backend.putUtxo(
                    txId = ByteArray(32),
                    index = 0,
                    script = ByteArray(0),
                    value = 0L,
                    height = -1L
                )
            }
        }

    @Test
    fun above_uint_range_put_utxo_height_throws() =
        runTest {
            val backend = newBackend()

            assertFailsWith<IllegalArgumentException> {
                backend.putUtxo(
                    txId = ByteArray(32),
                    index = 0,
                    script = ByteArray(0),
                    value = 0L,
                    height = 0x1_0000_0000L
                )
            }
        }

    @Test
    fun negative_branch_id_height_throws() {
        val backend = newBackend()

        assertFailsWith<IllegalArgumentException> { backend.getBranchIdForHeight(-1L) }
    }

    @Test
    fun above_uint_range_branch_id_height_throws() {
        val backend = newBackend()

        assertFailsWith<IllegalArgumentException> { backend.getBranchIdForHeight(0x1_0000_0000L) }
    }

    @Test
    fun negative_update_chain_tip_height_throws() =
        runTest {
            val backend = newBackend()

            assertFailsWith<IllegalArgumentException> { backend.updateChainTip(-1L) }
        }

    @Test
    fun above_uint_range_update_chain_tip_height_throws() =
        runTest {
            val backend = newBackend()

            assertFailsWith<IllegalArgumentException> { backend.updateChainTip(0x1_0000_0000L) }
        }

    @Test
    fun negative_find_block_metadata_height_throws() =
        runTest {
            val backend = newBackend()

            assertFailsWith<IllegalArgumentException> { backend.findBlockMetadata(-1L) }
        }

    @Test
    fun above_uint_range_find_block_metadata_height_throws() =
        runTest {
            val backend = newBackend()

            assertFailsWith<IllegalArgumentException> { backend.findBlockMetadata(0x1_0000_0000L) }
        }

    @Test
    fun boundary_find_block_metadata_heights_are_accepted() =
        runTest {
            val backend = newBackend()

            assertNull(backend.findBlockMetadata(0L))
            assertNull(backend.findBlockMetadata(0xFFFF_FFFFL))
        }

    @Test
    fun negative_rewind_block_metadata_height_throws() =
        runTest {
            val backend = newBackend()

            assertFailsWith<IllegalArgumentException> { backend.rewindBlockMetadataToHeight(-1L) }
        }

    @Test
    fun above_uint_range_rewind_block_metadata_height_throws() =
        runTest {
            val backend = newBackend()

            assertFailsWith<IllegalArgumentException> { backend.rewindBlockMetadataToHeight(0x1_0000_0000L) }
        }

    @Test
    fun boundary_rewind_block_metadata_heights_are_accepted() =
        runTest {
            val backend = newBackend()

            backend.rewindBlockMetadataToHeight(0L)
            backend.rewindBlockMetadataToHeight(0xFFFF_FFFFL)
        }

    @Test
    fun negative_scan_blocks_from_height_throws() =
        runTest {
            val backend = newBackend()

            assertFailsWith<IllegalArgumentException> {
                backend.scanBlocks(fromHeight = -1L, fromState = ByteArray(0), limit = 0L)
            }
        }

    @Test
    fun above_uint_range_scan_blocks_from_height_throws() =
        runTest {
            val backend = newBackend()

            assertFailsWith<IllegalArgumentException> {
                backend.scanBlocks(fromHeight = 0x1_0000_0000L, fromState = ByteArray(0), limit = 0L)
            }
        }

    @Test
    fun negative_scan_blocks_limit_throws() =
        runTest {
            val backend = newBackend()

            assertFailsWith<IllegalArgumentException> {
                backend.scanBlocks(fromHeight = 0L, fromState = ByteArray(0), limit = -1L)
            }
        }

    @Test
    fun negative_sapling_start_index_throws() =
        runTest {
            val backend = newBackend()

            assertFailsWith<IllegalArgumentException> {
                backend.putSubtreeRoots(
                    saplingStartIndex = -1L,
                    saplingRoots = emptyList(),
                    orchardStartIndex = 0L,
                    orchardRoots = emptyList(),
                    ironwoodStartIndex = 0L,
                    ironwoodRoots = emptyList()
                )
            }
        }

    @Test
    fun negative_orchard_start_index_throws() =
        runTest {
            val backend = newBackend()

            assertFailsWith<IllegalArgumentException> {
                backend.putSubtreeRoots(
                    saplingStartIndex = 0L,
                    saplingRoots = emptyList(),
                    orchardStartIndex = -1L,
                    orchardRoots = emptyList(),
                    ironwoodStartIndex = 0L,
                    ironwoodRoots = emptyList()
                )
            }
        }

    @Test
    fun negative_ironwood_start_index_throws() =
        runTest {
            val backend = newBackend()

            assertFailsWith<IllegalArgumentException> {
                backend.putSubtreeRoots(
                    saplingStartIndex = 0L,
                    saplingRoots = emptyList(),
                    orchardStartIndex = 0L,
                    orchardRoots = emptyList(),
                    ironwoodStartIndex = -1L,
                    ironwoodRoots = emptyList()
                )
            }
        }

    @Test
    fun negative_memo_output_index_throws() =
        runTest {
            val backend = newBackend()

            assertFailsWith<IllegalArgumentException> {
                backend.getMemoAsUtf8(txId = ByteArray(32), protocol = 0, outputIndex = -1)
            }
        }
}
