package com.zodl.slipstream.internal

import cash.z.ecc.android.sdk.Synchronizer.Status
import co.electriccoin.lightwallet.client.model.LightWalletEndpoint
import com.zodl.slipstream.model.SlipstreamEvent
import com.zodl.slipstream.model.SlipstreamSnapshot
import com.zodl.slipstream.model.SlipstreamWalletSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [SlipstreamEngine]'s status against a fake [EngineNative], driven by its own poll loop (a short
 * period): what the pure [nextStatus] cannot show - that the host's wish to run is recorded before the
 * native start, so a start that throws is reported, and that the previous tick's snapshot is the one
 * [serverReached] compares with.
 */
class SlipstreamEngineStatusTest {
    private class FakeNative : EngineNative {
        @Volatile
        var snapshot: SlipstreamSnapshot = snapshotOf(ENGINE_STATE_IDLE)

        @Volatile
        var startThrows = false

        val starts = AtomicInteger()

        override suspend fun ensureLoaded() = Unit

        override fun open(
            dbPath: String,
            serverHost: String,
            serverPort: Int,
            useTls: Boolean,
            networkId: Int,
            totalMemoryBytes: Long
        ): Long = HANDLE

        override fun start(
            handle: Long,
            ufvk: String?,
            birthdayHeight: Long,
            torDir: String?
        ): Boolean {
            starts.incrementAndGet()
            check(!startThrows) { "slipstream start failed" }
            return true
        }

        override fun stop(handle: Long): Boolean = true

        override fun stopForWalletChange(handle: Long): Boolean = true

        override fun snapshot(handle: Long): SlipstreamSnapshot = snapshot

        override fun drainEvents(handle: Long): Array<SlipstreamEvent> = emptyArray()

        override fun walletSummary(
            handle: Long,
            trustedConfirmations: Int,
            untrustedConfirmations: Int,
            allowZeroConfShielding: Boolean
        ): SlipstreamWalletSummary? = null

        override fun notifyTxChange(handle: Long): Boolean = true

        override fun free(handle: Long) = Unit
    }

    private class Harness {
        val native = FakeNative()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engine =
            SlipstreamEngine(
                dbPath = "wallet.db",
                endpoint = LightWalletEndpoint("relay.test", 443, true),
                networkId = 1,
                torDir = null,
                scope = scope,
                native = native,
                pollIntervalMs = POLL_MS
            )
        private val ticks = Channel<SlipstreamSnapshot>(Channel.UNLIMITED)

        init {
            engine.onTick = { ticks.send(it) }
        }

        suspend fun awaitStatus(expected: Status) = withTimeout(WAIT_MS) { engine.status.first { it == expected } }

        /** Waits for [count] ticks that start after this call (whatever was already recorded is dropped). */
        suspend fun awaitTicks(count: Int) {
            var drained = ticks.tryReceive()
            while (drained.isSuccess) drained = ticks.tryReceive()
            withTimeout(WAIT_MS) { repeat(count) { ticks.receive() } }
        }

        fun close() = engine.shutdown()
    }

    /**
     * Regression (review 2026-10-23, M1): every stop-then-restart site of the synchronizer catches a failed
     * `start` and keeps polling. The idle engine the host wanted running must read DISCONNECTED, not keep
     * SYNCED for as long as the outage lasts; a later start that works recovers within a tick.
     */
    @Test
    fun a_failed_restart_is_disconnected_on_the_next_tick_and_a_later_start_recovers() =
        runBlocking {
            val h = Harness()
            try {
                h.engine.open(totalMemoryBytes = 0)
                assertEquals(Status.INITIALIZING, h.engine.status.value, "opened, not started")

                h.native.snapshot = snapshotOf(ENGINE_STATE_DONE)
                h.engine.start(ufvk = null, birthday = 0)
                h.engine.startPolling()
                h.awaitStatus(Status.SYNCED)

                // The host stops it (background): idle ticks say nothing about the network.
                h.engine.stop()
                h.native.snapshot = snapshotOf(ENGINE_STATE_IDLE)
                h.awaitTicks(3)
                assertEquals(Status.SYNCED, h.engine.status.value, "a host-stopped engine keeps its status")

                // The restart throws: the engine stays idle while the host wants it running.
                h.native.startThrows = true
                assertFailsWith<IllegalStateException> { h.engine.start(ufvk = null, birthday = 0) }
                assertFalse(h.engine.isRunning, "isRunning follows the native start, which failed")
                h.awaitStatus(Status.DISCONNECTED)

                // A start that works: Done on the next snapshot, SYNCED within a tick.
                h.native.startThrows = false
                h.native.snapshot = snapshotOf(ENGINE_STATE_DONE)
                h.engine.start(ufvk = null, birthday = 0)
                assertTrue(h.engine.isRunning)
                h.awaitStatus(Status.SYNCED)
                assertEquals(3, h.native.starts.get())
            } finally {
                h.close()
            }
        }

    /**
     * Review 2026-10-23, M4: a failed initial pass alternates attempts (state 1) and backoff idles under the
     * host's 20 s grace. The status stays DISCONNECTED across the attempts and comes back once a snapshot
     * shows the server answered (here: blocks fetched).
     */
    @Test
    fun a_retried_initial_pass_stays_disconnected_until_a_snapshot_shows_the_server_answered() =
        runBlocking {
            val h = Harness()
            try {
                h.engine.open(totalMemoryBytes = 0)
                h.native.snapshot = snapshotOf(ENGINE_STATE_SYNCING)
                h.engine.start(ufvk = null, birthday = 0)
                h.engine.startPolling()
                h.awaitStatus(Status.SYNCING)

                h.native.snapshot = snapshotOf(ENGINE_STATE_IDLE)
                h.awaitStatus(Status.DISCONNECTED)

                h.native.snapshot = snapshotOf(ENGINE_STATE_SYNCING)
                h.awaitTicks(3)
                assertEquals(Status.DISCONNECTED, h.engine.status.value, "an attempt that got no answer")

                h.native.snapshot = snapshotOf(ENGINE_STATE_SYNCING, fetched = 5)
                h.awaitStatus(Status.SYNCING)

                h.native.snapshot = snapshotOf(ENGINE_STATE_SYNCING, fetched = 5)
                h.awaitTicks(3)
                assertEquals(Status.SYNCING, h.engine.status.value, "a quiet tick of a healthy pass")
            } finally {
                h.close()
            }
        }

    private companion object {
        const val HANDLE = 7L
        const val POLL_MS = 20L
        const val WAIT_MS = 10_000L

        fun snapshotOf(
            state: Int,
            fetched: Long = 0
        ) = SlipstreamSnapshot(
            chainTip = 100,
            fetchedBlocks = fetched,
            scannedBlocks = 0,
            enhancedTxs = 0,
            currentRangeEnd = 0,
            state = state,
            passTotalBlocks = 0,
            spendableHint = false,
            rangesCompleted = 0,
            isRecovering = false,
            progressPermille = 0,
            stalledSeconds = 0,
            tipFresh = false,
            txSetVersion = 0
        )
    }
}
