@file:Suppress("MaxLineLength")

package com.zodl.slipstream.internal

import cash.z.ecc.android.sdk.Synchronizer.Status
import cash.z.ecc.android.sdk.model.AccountUuid
import com.zodl.slipstream.model.SlipstreamAccountBalance
import com.zodl.slipstream.model.SlipstreamPoolBalance
import com.zodl.slipstream.model.SlipstreamSnapshot
import com.zodl.slipstream.model.SlipstreamWalletSummary
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EngineMappingTest {
    @Test
    fun engine_states_map_to_the_four_steady_statuses_while_running() {
        // idle while running = the engine itself went idle between retries (section 2.3)
        assertEquals(
            Status.DISCONNECTED,
            nextStatus(Status.SYNCED, ENGINE_STATE_IDLE, hostWantsRunning = true, serverReached = false)
        )
        assertEquals(
            Status.SYNCING,
            nextStatus(Status.SYNCED, ENGINE_STATE_SYNCING, hostWantsRunning = true, serverReached = false)
        )
        // error => "offer retry" (their enum has no ERROR)
        assertEquals(
            Status.DISCONNECTED,
            nextStatus(Status.SYNCED, ENGINE_STATE_ERROR, hostWantsRunning = true, serverReached = false)
        )
        assertEquals(
            Status.SYNCED,
            nextStatus(Status.SYNCING, ENGINE_STATE_DONE, hostWantsRunning = true, serverReached = true)
        )
    }

    @Test
    fun unknown_engine_state_degrades_to_disconnected_never_throws() {
        assertEquals(
            Status.DISCONNECTED,
            nextStatus(Status.SYNCED, 99, hostWantsRunning = true, serverReached = false)
        )
        assertEquals(
            Status.DISCONNECTED,
            nextStatus(Status.SYNCED, 99, hostWantsRunning = false, serverReached = false)
        )
    }

    /**
     * Regression, 2026-10-22 device logs: the app went to the background (`onBackground` -> `stop()`, the
     * native engine idle) while synced, a tick then read the idle snapshot, and the status flipped to
     * DISCONNECTED - frozen there for the whole background stay, shown as the "Disconnected" banner when
     * the activity came back, and fed to the app's connectivity fallback as a 20 s outage, with the
     * network fine throughout. An idle the host asked for carries no network information.
     */
    @Test
    fun an_idle_snapshot_after_the_host_stopped_the_engine_keeps_the_last_status() {
        var status = Status.INITIALIZING

        fun tick(
            state: Int,
            hostWantsRunning: Boolean,
            serverReached: Boolean = false
        ) {
            status = nextStatus(status, state, hostWantsRunning, serverReached)
        }

        tick(ENGINE_STATE_SYNCING, hostWantsRunning = true)
        tick(ENGINE_STATE_DONE, hostWantsRunning = true, serverReached = true)
        assertEquals(Status.SYNCED, status)

        // onBackground: stop() clears the host's wish, the native stop leaves the engine idle, a tick reads it.
        tick(ENGINE_STATE_IDLE, hostWantsRunning = false)
        assertEquals(Status.SYNCED, status, "a host-stopped engine is not a disconnected one")
        tick(ENGINE_STATE_IDLE, hostWantsRunning = false)
        assertEquals(Status.SYNCED, status)

        // onForeground: start() sets state 1 synchronously; the first tick recovers without any grace.
        tick(ENGINE_STATE_SYNCING, hostWantsRunning = true)
        assertEquals(Status.SYNCING, status)
        tick(ENGINE_STATE_DONE, hostWantsRunning = true, serverReached = true)
        assertEquals(Status.SYNCED, status)
    }

    /**
     * A restart that throws (`restartEngineAfter` in the synchronizer catches and logs it, polling goes on)
     * leaves an idle engine the host wanted running: DISCONNECTED on the next tick, not the last healthy
     * status forever. The host's wish is set before the native start, so it survives the throw.
     */
    @Test
    fun an_idle_snapshot_after_a_failed_restart_is_disconnected() {
        var status = Status.SYNCED
        // stop(): the host no longer wants it running, idle keeps SYNCED
        status = nextStatus(status, ENGINE_STATE_IDLE, hostWantsRunning = false, serverReached = false)
        assertEquals(Status.SYNCED, status)
        // start() threw: the host wants it running, the engine stayed idle
        status = nextStatus(status, ENGINE_STATE_IDLE, hostWantsRunning = true, serverReached = false)
        assertEquals(Status.DISCONNECTED, status)
        status = nextStatus(status, ENGINE_STATE_IDLE, hostWantsRunning = true, serverReached = false)
        assertEquals(Status.DISCONNECTED, status)
        // a later start that succeeds: state 1, then Done
        status = nextStatus(status, ENGINE_STATE_DONE, hostWantsRunning = true, serverReached = true)
        assertEquals(Status.SYNCED, status)
    }

    /** `open()` ticks once before `start()`: an engine that has not been started is initializing, not disconnected. */
    @Test
    fun the_first_snapshot_of_an_opened_but_not_started_engine_stays_initializing() =
        assertEquals(
            Status.INITIALIZING,
            nextStatus(Status.INITIALIZING, ENGINE_STATE_IDLE, hostWantsRunning = false, serverReached = false)
        )

    /**
     * The engine reports its own trouble through state 2 (error episode, `Error(3)` = endpoint unreachable after
     * `ENDPOINT_UNREACHABLE_THRESHOLD` consecutive probe failures) and clears it on the next good probe: the
     * status follows within one tick, no lifecycle event needed.
     */
    @Test
    fun an_error_episode_and_its_recovery_are_reflected_within_one_tick() {
        var status = Status.SYNCED
        status = nextStatus(status, ENGINE_STATE_ERROR, hostWantsRunning = true, serverReached = false)
        assertEquals(Status.DISCONNECTED, status)
        status = nextStatus(status, ENGINE_STATE_DONE, hostWantsRunning = true, serverReached = true)
        assertEquals(Status.SYNCED, status)
    }

    /**
     * A failed initial pass: the engine idles between attempts (`sync_retry_backoff` 3, 6, 12 s ... - under the
     * host's 20 s grace) and is in state 1 during each. The status stays DISCONNECTED across the attempts, so
     * the outage is one steady episode the host can act on, and ends as soon as a snapshot shows the server
     * was reached.
     */
    @Test
    fun an_engine_retrying_a_failed_pass_stays_disconnected_until_the_server_is_reached() {
        var status = Status.INITIALIZING
        status = nextStatus(status, ENGINE_STATE_SYNCING, hostWantsRunning = true, serverReached = false)
        assertEquals(Status.SYNCING, status, "the first attempt of a session is syncing, nothing failed yet")
        status = nextStatus(status, ENGINE_STATE_IDLE, hostWantsRunning = true, serverReached = false)
        assertEquals(Status.DISCONNECTED, status)
        status = nextStatus(status, ENGINE_STATE_SYNCING, hostWantsRunning = true, serverReached = false)
        assertEquals(Status.DISCONNECTED, status, "a retry that has not reached the server is still the outage")
        status = nextStatus(status, ENGINE_STATE_IDLE, hostWantsRunning = true, serverReached = false)
        assertEquals(Status.DISCONNECTED, status)
        status = nextStatus(status, ENGINE_STATE_SYNCING, hostWantsRunning = true, serverReached = true)
        assertEquals(Status.SYNCING, status, "the server answered: syncing again")
        status = nextStatus(status, ENGINE_STATE_SYNCING, hostWantsRunning = true, serverReached = false)
        assertEquals(Status.SYNCING, status, "a quiet tick within a healthy pass is not an outage")
    }

    @Test
    fun the_server_is_reached_when_a_pass_completes_or_the_tip_latches_fresh_or_a_network_counter_advances() {
        val quiet =
            snapshot(
                state = ENGINE_STATE_SYNCING,
                chainTip = 100,
                fetched = 10,
                scanned = 5,
                enhanced = 1,
                permille = 400
            )

        assertFalse(serverReached(previous = quiet, snap = quiet), "nothing moved")
        assertFalse(serverReached(previous = null, snap = quiet), "no previous snapshot to compare with")
        assertTrue(serverReached(previous = quiet, snap = quiet.copy(state = ENGINE_STATE_DONE)))
        assertTrue(serverReached(previous = null, snap = quiet.copy(state = ENGINE_STATE_DONE)))
        assertTrue(serverReached(previous = quiet, snap = quiet.copy(tipFresh = true)))
        val fresh = quiet.copy(tipFresh = true)
        assertFalse(serverReached(previous = fresh, snap = fresh), "already fresh")
        assertTrue(serverReached(previous = quiet, snap = quiet.copy(chainTip = 101)))
        assertTrue(serverReached(previous = quiet, snap = quiet.copy(fetchedBlocks = 11)))
        assertTrue(serverReached(previous = quiet, snap = quiet.copy(scannedBlocks = 6)))
        assertTrue(serverReached(previous = quiet, snap = quiet.copy(enhancedTxs = 2)))
        assertTrue(serverReached(previous = quiet, snap = quiet.copy(progressPermille = 401)))
        val newPass = quiet.copy(fetchedBlocks = 0, scannedBlocks = 0)
        assertFalse(serverReached(previous = quiet, snap = newPass), "a new pass resetting its counters")
        assertFalse(serverReached(previous = quiet, snap = quiet.copy(stalledSeconds = 30, state = ENGINE_STATE_IDLE)))
    }

    private fun snapshot(
        state: Int,
        chainTip: Long,
        fetched: Long,
        scanned: Long,
        enhanced: Long,
        permille: Int
    ) = SlipstreamSnapshot(
        chainTip = chainTip,
        fetchedBlocks = fetched,
        scannedBlocks = scanned,
        enhancedTxs = enhanced,
        currentRangeEnd = 0,
        state = state,
        passTotalBlocks = 0,
        spendableHint = false,
        rangesCompleted = 0,
        isRecovering = false,
        progressPermille = permille,
        stalledSeconds = 0,
        tipFresh = false,
        txSetVersion = 0
    )

    @Test
    fun permille_endpoints_and_midpoint() {
        assertEquals(0.0f, permilleToPercentDecimal(0).decimal)
        assertEquals(0.5f, permilleToPercentDecimal(500).decimal)
        assertEquals(1.0f, permilleToPercentDecimal(1000).decimal)
    }

    @Test
    fun out_of_contract_permille_clamps_instead_of_crashing() {
        assertEquals(1.0f, permilleToPercentDecimal(1001).decimal)
        assertEquals(0.0f, permilleToPercentDecimal(-1).decimal)
    }

    @Test
    fun tip_stale_and_not_recovering_masks_shielded_spendable_into_pending() {
        val uuid = ByteArray(16) { it.toByte() }
        val summary = summaryWith(uuid, sapling = SlipstreamPoolBalance(100, 10, 5, 0))

        val balances = summary.toAccountBalances(isRecovering = false, tipFresh = false)
        val balance = balances.getValue(AccountUuid.new(uuid))

        assertEquals(0L, balance.sapling.available.value)
        assertEquals(10L, balance.sapling.changePending.value)
        assertEquals(105L, balance.sapling.valuePending.value) // 5 (pending) + 100 (masked spendable)
    }

    @Test
    fun tip_fresh_never_masks() {
        val uuid = ByteArray(16) { it.toByte() }
        val summary = summaryWith(uuid, sapling = SlipstreamPoolBalance(100, 10, 5, 0))

        val balances = summary.toAccountBalances(isRecovering = false, tipFresh = true)
        val balance = balances.getValue(AccountUuid.new(uuid))

        assertEquals(100L, balance.sapling.available.value)
        assertEquals(10L, balance.sapling.changePending.value)
        assertEquals(5L, balance.sapling.valuePending.value)
    }

    @Test
    fun locked_value_surfaces_on_walletbalance_and_is_excluded_from_total() {
        val uuid = ByteArray(16) { it.toByte() }
        val summary = summaryWith(uuid, sapling = SlipstreamPoolBalance(100, 10, 5, 25))

        val balances = summary.toAccountBalances(isRecovering = false, tipFresh = true)
        val balance = balances.getValue(AccountUuid.new(uuid))

        assertEquals(25L, balance.sapling.locked.value)
        assertEquals(115L, balance.sapling.total.value) // 100 + 10 + 5, locked deliberately excluded
    }

    @Test
    fun locked_value_survives_stale_tip_masking_unmodified() {
        val uuid = ByteArray(16) { it.toByte() }
        val summary = summaryWith(uuid, sapling = SlipstreamPoolBalance(100, 10, 5, 25))

        val balances = summary.toAccountBalances(isRecovering = false, tipFresh = false)
        val balance = balances.getValue(AccountUuid.new(uuid))

        assertEquals(25L, balance.sapling.locked.value) // masking reclassifies spendable, never touches locked
    }

    @Test
    fun recovery_never_masks_even_when_tip_is_stale() {
        val uuid = ByteArray(16) { it.toByte() }
        val summary = summaryWith(uuid, sapling = SlipstreamPoolBalance(100, 10, 5, 0))

        val balances = summary.toAccountBalances(isRecovering = true, tipFresh = false)
        val balance = balances.getValue(AccountUuid.new(uuid))

        assertEquals(100L, balance.sapling.available.value)
    }

    @Test
    fun unshielded_is_never_masked() {
        val uuid = ByteArray(16) { it.toByte() }
        val summary = summaryWith(uuid, sapling = SlipstreamPoolBalance(0, 0, 0, 0), unshielded = 42)

        val balances = summary.toAccountBalances(isRecovering = false, tipFresh = false)
        val balance = balances.getValue(AccountUuid.new(uuid))

        assertEquals(42L, balance.unshielded.value)
    }

    @Test
    fun multiple_accounts_all_map() {
        val uuidA = ByteArray(16) { 1 }
        val uuidB = ByteArray(16) { 2 }
        val summary =
            SlipstreamWalletSummary(
                accountBalances =
                    arrayOf(
                        accountBalance(uuidA, SlipstreamPoolBalance(1, 0, 0, 0), SlipstreamPoolBalance(0, 0, 0, 0), 0),
                        accountBalance(uuidB, SlipstreamPoolBalance(0, 0, 0, 0), SlipstreamPoolBalance(2, 0, 0, 0), 0)
                    ),
                chainTipHeight = 0,
                fullyScannedHeight = 0,
                scanProgress = null,
                recoveryProgress = null,
                nextSaplingSubtreeIndex = 0,
                nextOrchardSubtreeIndex = 0,
                nextIronwoodSubtreeIndex = 0
            )
        val balances = summary.toAccountBalances(isRecovering = false, tipFresh = true)
        assertTrue(balances.containsKey(AccountUuid.new(uuidA)))
        assertTrue(balances.containsKey(AccountUuid.new(uuidB)))
    }

    @Test
    fun ironwood_balance_surfaces_when_tip_fresh() {
        val uuid = ByteArray(16) { it.toByte() }
        val summary =
            summaryWith(
                uuid,
                sapling = SlipstreamPoolBalance(0, 0, 0, 0),
                ironwood = SlipstreamPoolBalance(99, 0, 0, 0)
            )

        val balances = summary.toAccountBalances(isRecovering = false, tipFresh = true)
        val balance = balances.getValue(AccountUuid.new(uuid))

        assertEquals(99L, balance.ironwood.available.value)
    }

    @Test
    fun a_fresh_tip_is_effective_regardless_of_the_stale_tick_count() {
        assertTrue(effectiveTipFresh(tipFresh = true, consecutiveStaleTicks = 0))
        assertTrue(effectiveTipFresh(tipFresh = true, consecutiveStaleTicks = STALE_MASK_FAIL_OPEN_TICKS))
    }

    /**
     * MOB-1667: the mask must fail open once it has been applied to live observations for
     * [STALE_MASK_FAIL_OPEN_TICKS] consecutive ticks (15 minutes at the 2 s poll interval) - a
     * `tipFresh` that never latches otherwise pins spendable at zero forever.
     */
    @Test
    fun a_stale_tip_stops_masking_only_once_the_fail_open_threshold_is_reached() {
        assertFalse(effectiveTipFresh(tipFresh = false, consecutiveStaleTicks = 0))
        assertFalse(effectiveTipFresh(tipFresh = false, consecutiveStaleTicks = STALE_MASK_FAIL_OPEN_TICKS - 1))
        assertTrue(effectiveTipFresh(tipFresh = false, consecutiveStaleTicks = STALE_MASK_FAIL_OPEN_TICKS))
        assertTrue(effectiveTipFresh(tipFresh = false, consecutiveStaleTicks = STALE_MASK_FAIL_OPEN_TICKS + 1))
    }

    /** 450 ticks at [SlipstreamEngine.POLL_INTERVAL_MS] is the intended 15 minutes. */
    @Test
    fun the_fail_open_threshold_is_fifteen_minutes_of_ticks() =
        assertEquals(15 * 60 * 1_000L, STALE_MASK_FAIL_OPEN_TICKS * SlipstreamEngine.POLL_INTERVAL_MS)

    private fun accountBalance(
        uuid: ByteArray,
        sapling: SlipstreamPoolBalance,
        orchard: SlipstreamPoolBalance,
        unshielded: Long,
        ironwood: SlipstreamPoolBalance = SlipstreamPoolBalance(0, 0, 0, 0)
    ) = SlipstreamAccountBalance(uuid, sapling, orchard, ironwood = ironwood, unshielded = unshielded)

    private fun summaryWith(
        uuid: ByteArray,
        sapling: SlipstreamPoolBalance,
        orchard: SlipstreamPoolBalance = SlipstreamPoolBalance(0, 0, 0, 0),
        unshielded: Long = 0,
        ironwood: SlipstreamPoolBalance = SlipstreamPoolBalance(0, 0, 0, 0)
    ) = SlipstreamWalletSummary(
        accountBalances = arrayOf(accountBalance(uuid, sapling, orchard, unshielded, ironwood)),
        chainTipHeight = 0,
        fullyScannedHeight = 0,
        scanProgress = null,
        recoveryProgress = null,
        nextSaplingSubtreeIndex = 0,
        nextOrchardSubtreeIndex = 0,
        nextIronwoodSubtreeIndex = 0
    )
}
