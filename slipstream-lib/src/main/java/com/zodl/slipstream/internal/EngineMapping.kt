@file:Suppress("MagicNumber")

package com.zodl.slipstream.internal

import cash.z.ecc.android.sdk.Synchronizer.Status
import cash.z.ecc.android.sdk.model.AccountBalance
import cash.z.ecc.android.sdk.model.AccountUuid
import cash.z.ecc.android.sdk.model.PercentDecimal
import cash.z.ecc.android.sdk.model.WalletBalance
import cash.z.ecc.android.sdk.model.Zatoshi
import com.zodl.slipstream.model.SlipstreamPoolBalance
import com.zodl.slipstream.model.SlipstreamWalletSummary

/**
 * The [Status] one snapshot leaves the engine in, given the status it had ([previous]) and whether the
 * host has the engine [running] (between `start()` and `stop()`).
 *
 * `state` 1 -> SYNCING, 3 -> SYNCED, 2 -> DISCONNECTED (an error episode, see [ErrorEpisodeGate]; the
 * public enum has no ERROR), any unknown value -> DISCONNECTED. The idle state 0 depends on who made
 * the engine idle:
 * - While the host has it running, the engine only goes idle on its own between the retries of a
 *   failed initial pass or Tor bootstrap (`session.rs`), i.e. when it cannot reach the server:
 *   DISCONNECTED (`KOTLIN_ROSETTA.md` section 2.3 - iOS parity). `start` sets state 1 synchronously, so
 *   a running engine never reports a healthy idle.
 * - While the host has it stopped (before `start`, after `stop`, backgrounded), idle is the expected
 *   state and says nothing about the network: the status stays [previous]. Mapping it to DISCONNECTED
 *   showed a "Disconnected" banner on every return to the foreground and fed the app's connectivity
 *   fallback a false outage (2026-10-22 device logs: `MainActivity.onStop` 12:29:02 -> status
 *   DISCONNECTED -> "Home message: Disconnected shown" 12:29:05, with the engine healthy a second before
 *   and again a second after the restart).
 *
 * `INITIALIZING`/`STOPPED` are lifecycle values the synchronizer sets outside the tick (pre-open /
 * after close), never here: a snapshot of an opened but not yet started engine therefore leaves the
 * status at INITIALIZING.
 */
internal fun nextStatus(
    previous: Status,
    state: Int,
    running: Boolean
): Status =
    when (state) {
        ENGINE_STATE_IDLE -> if (running) Status.DISCONNECTED else previous
        ENGINE_STATE_SYNCING -> Status.SYNCING
        ENGINE_STATE_DONE -> Status.SYNCED
        else -> Status.DISCONNECTED // 2 error (offer retry), and any unknown value
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
