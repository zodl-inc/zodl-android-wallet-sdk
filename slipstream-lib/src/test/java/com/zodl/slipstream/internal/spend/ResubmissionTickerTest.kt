package com.zodl.slipstream.internal.spend

import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals

class ResubmissionTickerTest {
    @Test
    fun does_nothing_before_the_tick_threshold() =
        runBlocking {
            var findCalls = 0
            val ticker =
                ResubmissionTicker(
                    findCandidates = {
                        findCalls++
                        emptyList()
                    },
                    resubmit = {},
                    notifyTxChange = {}
                )
            repeat(ResubmissionTicker.RESUBMIT_EVERY - 1) { ticker.onTick(isSynced = true, chainTip = 100L) }
            assertEquals(0, findCalls)
        }

    @Test
    fun scans_exactly_every_resubmit_every_ticks_while_synced() =
        runBlocking {
            var findCalls = 0
            val ticker =
                ResubmissionTicker(
                    findCandidates = {
                        findCalls++
                        emptyList()
                    },
                    resubmit = {},
                    notifyTxChange = {}
                )
            repeat(ResubmissionTicker.RESUBMIT_EVERY) { ticker.onTick(isSynced = true, chainTip = 100L) }
            assertEquals(1, findCalls)
        }

    @Test
    fun not_synced_resets_the_counter_instead_of_scanning() =
        runBlocking {
            var findCalls = 0
            val ticker =
                ResubmissionTicker(
                    findCandidates = {
                        findCalls++
                        emptyList()
                    },
                    resubmit = {},
                    notifyTxChange = {}
                )
            repeat(ResubmissionTicker.RESUBMIT_EVERY - 1) { ticker.onTick(isSynced = true, chainTip = 100L) }
            ticker.onTick(isSynced = false, chainTip = 100L)
            repeat(ResubmissionTicker.RESUBMIT_EVERY - 1) { ticker.onTick(isSynced = true, chainTip = 100L) }
            assertEquals(0, findCalls)
        }

    @Test
    fun resubmits_every_candidate_and_pokes_exactly_once() =
        runBlocking {
            val resubmitted = mutableListOf<ByteArray>()
            var notifyCalls = 0
            val candidates =
                listOf(
                    ResubmissionCandidate(txId = byteArrayOf(1), raw = byteArrayOf(9)),
                    ResubmissionCandidate(txId = byteArrayOf(2), raw = byteArrayOf(8))
                )
            val ticker =
                ResubmissionTicker(
                    findCandidates = { candidates },
                    resubmit = { resubmitted.add(it.txId) },
                    notifyTxChange = { notifyCalls++ }
                )
            repeat(ResubmissionTicker.RESUBMIT_EVERY) { ticker.onTick(isSynced = true, chainTip = 100L) }
            assertEquals(2, resubmitted.size)
            assertEquals(1, notifyCalls)
        }

    @Test
    fun no_candidates_means_no_poke() =
        runBlocking {
            var notifyCalls = 0
            val ticker =
                ResubmissionTicker(
                    findCandidates = { emptyList() },
                    resubmit = {},
                    notifyTxChange = { notifyCalls++ }
                )
            repeat(ResubmissionTicker.RESUBMIT_EVERY) { ticker.onTick(isSynced = true, chainTip = 100L) }
            assertEquals(0, notifyCalls)
        }

    @Test
    fun a_rejected_candidate_is_not_resubmitted_and_every_candidate_is_retained() =
        runBlocking {
            val rejected = ResubmissionCandidate(byteArrayOf(1), byteArrayOf(1))
            val pending = ResubmissionCandidate(byteArrayOf(2), byteArrayOf(2))
            val resubmitted = mutableListOf<ResubmissionCandidate>()
            var retained: List<ResubmissionCandidate>? = null
            val ticker =
                ResubmissionTicker(
                    findCandidates = { listOf(rejected, pending) },
                    resubmit = { resubmitted += it },
                    notifyTxChange = {},
                    isRejected = { it === rejected },
                    retainRejections = { retained = it }
                )

            repeat(ResubmissionTicker.RESUBMIT_EVERY) { ticker.onTick(isSynced = true, chainTip = 100L) }

            assertEquals(listOf(pending), resubmitted)
            assertEquals(listOf(rejected, pending), retained)
        }

    @Test
    fun the_retention_scope_is_taken_before_the_candidates_are_read() =
        runBlocking {
            val events = mutableListOf<String>()
            val ticker =
                ResubmissionTicker(
                    findCandidates = {
                        events += "scan"
                        emptyList()
                    },
                    resubmit = {},
                    notifyTxChange = {},
                    retainRejections = { events += "retain" },
                    beforeScan = { events += "begin" }
                )

            repeat(ResubmissionTicker.RESUBMIT_EVERY) { ticker.onTick(isSynced = true, chainTip = 100L) }

            assertEquals(listOf("begin", "scan", "retain"), events)
        }
}
