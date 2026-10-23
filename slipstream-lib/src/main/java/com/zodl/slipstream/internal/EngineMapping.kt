@file:Suppress("MagicNumber")

package com.zodl.slipstream.internal

import cash.z.ecc.android.sdk.Synchronizer.Status
import cash.z.ecc.android.sdk.model.AccountBalance
import cash.z.ecc.android.sdk.model.AccountUuid
import cash.z.ecc.android.sdk.model.PercentDecimal
import cash.z.ecc.android.sdk.model.WalletBalance
import cash.z.ecc.android.sdk.model.Zatoshi
import com.zodl.slipstream.model.SlipstreamPoolBalance
import com.zodl.slipstream.model.SlipstreamSnapshot
import com.zodl.slipstream.model.SlipstreamWalletSummary

/**
 * The [Status] one snapshot leaves the engine in, given the status it had ([previous]), whether the
 * host wants the engine running ([hostWantsRunning]: from its start request, successful or not, until
 * `stop()`), and whether the snapshot shows the server was reached since the previous one
 * ([serverReached]).
 *
 * `state` 3 -> SYNCED, 2 -> DISCONNECTED (an error episode, see [ErrorEpisodeGate]; the public enum has
 * no ERROR), any unknown value -> DISCONNECTED. The idle state 0 depends on who made the engine idle:
 * - While the host wants it running, the engine only goes idle on its own between the retries of a
 *   failed initial pass or Tor bootstrap (`session.rs`), i.e. when it cannot reach the server:
 *   DISCONNECTED (`KOTLIN_ROSETTA.md` section 2.3 - iOS parity). `start` sets state 1 synchronously, so
 *   a running engine never reports a healthy idle; and a start that threw leaves an idle engine the
 *   host wanted running, DISCONNECTED too rather than frozen on the last healthy status.
 * - While the host has it stopped (before `start`, after `stop`, backgrounded), idle is the expected
 *   state and says nothing about the network: the status stays [previous]. Mapping it to DISCONNECTED
 *   showed a "Disconnected" banner on every return to the foreground and fed the app's connectivity
 *   fallback a false outage (2026-10-22 device logs: `MainActivity.onStop` 12:29:02 -> status
 *   DISCONNECTED -> "Home message: Disconnected shown" 12:29:05, with the engine healthy a second before
 *   and again a second after the restart).
 *
 * `state` 1 -> SYNCING, except right after a DISCONNECTED: the engine retrying a failed initial pass is
 * in state 1 for each attempt and idle for the backoff between them (`session.rs`: `sync_retry_backoff`
 * 3, 6, 12, 24, 30 s; `pass_retry_sleep` 5 and 15 s inside an attempt; `Error(3)` is only raised by the
 * follow loop), so a sustained outage would otherwise flap SYNCING/DISCONNECTED under the host's 20 s
 * grace and never show as an outage at all. The status therefore stays DISCONNECTED until a snapshot
 * shows the server was reached ([serverReached]); a healthy pass does so within its first ticks.
 *
 * `INITIALIZING`/`STOPPED` are lifecycle values the synchronizer sets outside the tick (pre-open /
 * after close), never here: a snapshot of an opened but not yet started engine therefore leaves the
 * status at INITIALIZING.
 */
internal fun nextStatus(
    previous: Status,
    state: Int,
    hostWantsRunning: Boolean,
    serverReached: Boolean
): Status =
    when (state) {
        ENGINE_STATE_IDLE -> if (hostWantsRunning) Status.DISCONNECTED else previous
        ENGINE_STATE_SYNCING ->
            if (previous == Status.DISCONNECTED && !serverReached) Status.DISCONNECTED else Status.SYNCING
        ENGINE_STATE_DONE -> Status.SYNCED
        else -> Status.DISCONNECTED // 2 error (offer retry), and any unknown value
    }

/**
 * Whether [snap] shows the engine reached the server since [previous] (the previous tick's snapshot,
 * null on the first): the pass completed (state 3), the chain tip latched fresh (only after a
 * successful `update_chain_tip`, `tip_fresh_now` in the JNI crate), or a network-fed counter advanced -
 * the chain tip, blocks fetched or scanned, transactions enhanced, progress. A retry of a failed pass
 * that gets no answer moves none of them (counters that reset to 0 at a new pass do not count as
 * advancing). Used by [nextStatus] to end a DISCONNECTED episode.
 */
internal fun serverReached(
    previous: SlipstreamSnapshot?,
    snap: SlipstreamSnapshot
): Boolean {
    if (snap.state == ENGINE_STATE_DONE) return true
    if (previous == null) return false
    return (snap.tipFresh && !previous.tipFresh) ||
        snap.chainTip > previous.chainTip ||
        snap.fetchedBlocks > previous.fetchedBlocks ||
        snap.scannedBlocks > previous.scannedBlocks ||
        snap.enhancedTxs > previous.enhancedTxs ||
        snap.progressPermille > previous.progressPermille
}

/** `SlipstreamSnapshot.state` values (`FFI_JNI_CONTRACT.md`: 0 idle, 1 syncing, 2 error, 3 done). */
internal const val ENGINE_STATE_IDLE = 0
internal const val ENGINE_STATE_SYNCING = 1
internal const val ENGINE_STATE_ERROR = 2
internal const val ENGINE_STATE_DONE = 3

/**
 * Render, never derive (DECISIONS.md D11) - the only arithmetic allowed is the unit change;
 * [PercentDecimal.newLenient] tolerates any out-of-range permille without throwing (the canonical
 * progress idiom).
 */
internal fun permilleToPercentDecimal(permille: Int): PercentDecimal = PercentDecimal.newLenient(permille / 1000f)

private fun SlipstreamPoolBalance.toWalletBalance() =
    WalletBalance(
        Zatoshi(spendableValue),
        Zatoshi(changePendingConfirmation),
        Zatoshi(valuePendingSpendability),
        Zatoshi(lockedValue)
    )

/**
 * `FFI_JNI_CONTRACT.md` section 5.5 mask: while NOT recovering AND tip is stale, shield-pool
 * spendable -> pending (a spendable claim about an unverified tip is a bug). Recovery balances
 * are never masked (safe by construction). The unshielded arm is left unmasked in v1 -
 * `AccountBalance.unshielded` is a bare [Zatoshi] with no pending slot; zeroing it would vanish
 * funds (`KOTLIN_ROSETTA.md` section 4.5 / OQ-2).
 */
private fun SlipstreamPoolBalance.maskIfStale(mask: Boolean): SlipstreamPoolBalance =
    if (!mask) {
        this
    } else {
        SlipstreamPoolBalance(0, changePendingConfirmation, valuePendingSpendability + spendableValue, lockedValue)
    }

/**
 * How many consecutive stale-tip ticks the mask tolerates before it fails open - 450 ticks at the
 * 2 s poll interval, i.e. 15 minutes.
 */
internal const val STALE_MASK_FAIL_OPEN_TICKS = 450

/**
 * The tip freshness [toAccountBalances] should mask against, given the engine's own `tipFresh` and
 * how many consecutive ticks have reported a stale tip.
 *
 * `tipFresh` only latches once the engine's serial network prologue completes, so any condition
 * that keeps the prologue from finishing (a wedged sync, a dead network session, MOB-1667's
 * permanent migration pause) pins spendable at zero indefinitely while the total balance stays
 * correct - the wallet looks funded but nothing can be spent, forever. Past
 * [STALE_MASK_FAIL_OPEN_TICKS] live ticks the mask therefore fails open: showing spendable against
 * an unverified tip cannot lose funds (a spend built on a stale tip fails at propose or broadcast),
 * whereas hiding it indefinitely bricks the wallet.
 */
internal fun effectiveTipFresh(
    tipFresh: Boolean,
    consecutiveStaleTicks: Int
): Boolean = tipFresh || consecutiveStaleTicks >= STALE_MASK_FAIL_OPEN_TICKS

/**
 * Maps the engine's phase-resolving summary to the SDK's public balance model. `ironwood` is
 * read straight from the engine's own `SlipstreamAccountBalance.ironwood` field, which the JNI
 * layer populates from the linked librustzcash summary (`balance.ironwood_balance()`), so migrated
 * Orchard→Ironwood funds recorded in the wallet DB surface here.
 */
internal fun SlipstreamWalletSummary.toAccountBalances(
    isRecovering: Boolean,
    tipFresh: Boolean
): Map<AccountUuid, AccountBalance> {
    val mask = !isRecovering && !tipFresh
    return accountBalances.associate { ab ->
        AccountUuid.new(ab.accountUuid) to
            AccountBalance(
                sapling = ab.sapling.maskIfStale(mask).toWalletBalance(),
                orchard = ab.orchard.maskIfStale(mask).toWalletBalance(),
                ironwood = ab.ironwood.maskIfStale(mask).toWalletBalance(),
                unshielded = Zatoshi(ab.unshielded)
            )
    }
}
