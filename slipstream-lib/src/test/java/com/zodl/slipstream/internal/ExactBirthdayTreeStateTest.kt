package com.zodl.slipstream.internal

import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.SdkFlags
import co.electriccoin.lightwallet.client.CombinedWalletClient
import co.electriccoin.lightwallet.client.ServiceMode
import co.electriccoin.lightwallet.client.model.BlockHeightUnsafe
import co.electriccoin.lightwallet.client.model.Response
import co.electriccoin.lightwallet.client.model.TreeStateUnsafe
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.mockito.Mockito.mock
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * [fetchExactBirthdayTreeState]: the tree state one block below the birthday, fetched over a fresh Tor
 * circuit whenever Tor is enabled and directly otherwise, and `null` - the bundled checkpoint - whenever
 * the server cannot provide a usable one.
 */
class ExactBirthdayTreeStateTest {
    @Test
    fun fetchesTheTreeStateOneBlockBelowTheBirthday() =
        runBlocking<Unit> {
            val client = RecordingClient { Response.Success(TreeStateUnsafe(TREE_STATE)) }

            val treeState = fetchExactBirthdayTreeState(client, CLEARNET, BlockHeight.new(BIRTHDAY))

            assertContentEquals(TREE_STATE, assertNotNull(treeState).encoded)
            assertEquals(listOf(BlockHeightUnsafe(BIRTHDAY - 1)), client.heights)
        }

    @Test
    fun withTorTheFetchGoesOverAFreshTorCircuitOnly() =
        runBlocking<Unit> {
            val client = RecordingClient { Response.Success(TreeStateUnsafe(TREE_STATE)) }

            fetchExactBirthdayTreeState(client, TOR, BlockHeight.new(BIRTHDAY))

            assertEquals(listOf<ServiceMode>(ServiceMode.UniqueTor), client.serviceModes)
        }

    @Test
    fun withoutTorTheFetchGoesDirectly() =
        runBlocking<Unit> {
            val client = RecordingClient { Response.Success(TreeStateUnsafe(TREE_STATE)) }

            fetchExactBirthdayTreeState(client, CLEARNET, BlockHeight.new(BIRTHDAY))

            assertEquals(listOf<ServiceMode>(ServiceMode.Direct), client.serviceModes)
        }

    /** A Tor failure is never retried directly: the wallet starts at the checkpoint instead. */
    @Test
    fun aFailedTorFetchFallsBackToTheCheckpointWithoutAnotherRequest() =
        runBlocking<Unit> {
            val client = RecordingClient { Response.Failure.OverTor(IllegalStateException("no circuit")) }

            assertNull(fetchExactBirthdayTreeState(client, TOR, BlockHeight.new(BIRTHDAY)))
            assertEquals(listOf<ServiceMode>(ServiceMode.UniqueTor), client.serviceModes)
        }

    @Test
    fun aFailedFetchFallsBackToTheCheckpoint() =
        runBlocking<Unit> {
            val client = RecordingClient { Response.Failure.Connection(IllegalStateException("offline")) }

            assertNull(fetchExactBirthdayTreeState(client, CLEARNET, BlockHeight.new(BIRTHDAY)))
        }

    @Test
    fun aFetchThatThrowsFallsBackToTheCheckpoint() =
        runBlocking<Unit> {
            val client = RecordingClient { throw IllegalStateException("client gone") }

            assertNull(fetchExactBirthdayTreeState(client, CLEARNET, BlockHeight.new(BIRTHDAY)))
        }

    @Test
    fun aFetchThatTimesOutFallsBackToTheCheckpoint() =
        runBlocking<Unit> {
            val client = RecordingClient { awaitCancellation() }

            assertNull(
                fetchExactBirthdayTreeState(client, TOR, BlockHeight.new(BIRTHDAY), timeout = 50.milliseconds)
            )
            assertEquals(1, client.heights.size)
        }

    @Test
    fun anEmptyTreeStateFallsBackToTheCheckpoint() =
        runBlocking<Unit> {
            val client = RecordingClient { Response.Success(TreeStateUnsafe(ByteArray(0))) }

            assertNull(fetchExactBirthdayTreeState(client, CLEARNET, BlockHeight.new(BIRTHDAY)))
        }

    @Test
    fun theTimeoutIsBoundedAndLeavesRoomForATorCircuit() {
        assertTrue(EXACT_BIRTHDAY_TREE_STATE_TIMEOUT.isPositive())
        assertTrue(EXACT_BIRTHDAY_TREE_STATE_TIMEOUT.inWholeSeconds in MIN_TIMEOUT_SECONDS..MAX_TIMEOUT_SECONDS)
    }

    /** A client that answers every tree state request with [answer] and records the requests. */
    private class RecordingClient(
        private val answer: suspend () -> Response<TreeStateUnsafe>
    ) : CombinedWalletClient by mock(CombinedWalletClient::class.java) {
        val heights = mutableListOf<BlockHeightUnsafe>()
        val serviceModes = mutableListOf<ServiceMode>()

        override suspend fun getTreeState(
            height: BlockHeightUnsafe,
            serviceMode: ServiceMode
        ): Response<TreeStateUnsafe> {
            heights += height
            serviceModes += serviceMode
            return answer()
        }
    }

    private companion object {
        const val BIRTHDAY = 3_200_000L
        const val MIN_TIMEOUT_SECONDS = 10L
        const val MAX_TIMEOUT_SECONDS = 60L
        val TREE_STATE = byteArrayOf(9, 8, 7)
        val TOR = SdkFlags(isTorEnabled = true, isExchangeRateEnabled = false)
        val CLEARNET = SdkFlags(isTorEnabled = false, isExchangeRateEnabled = false)
    }
}
