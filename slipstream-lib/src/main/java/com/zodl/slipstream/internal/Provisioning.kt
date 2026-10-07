package com.zodl.slipstream.internal

import android.content.Context
import cash.z.ecc.android.sdk.WalletInitMode
import cash.z.ecc.android.sdk.internal.Twig
import cash.z.ecc.android.sdk.internal.model.TreeState
import cash.z.ecc.android.sdk.internal.model.WalletSummary
import cash.z.ecc.android.sdk.model.AccountCreateSetup
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.FirstClassByteArray
import cash.z.ecc.android.sdk.model.SdkFlags
import cash.z.ecc.android.sdk.model.ZcashNetwork
import cash.z.ecc.android.sdk.tool.CheckpointTool
import co.electriccoin.lightwallet.client.CombinedWalletClient
import co.electriccoin.lightwallet.client.ServiceMode
import co.electriccoin.lightwallet.client.model.BlockHeightUnsafe
import co.electriccoin.lightwallet.client.model.Response
import co.electriccoin.lightwallet.client.model.TreeStateUnsafe
import com.zodl.slipstream.model.SlipstreamRestoreAnchor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * `FFI_JNI_CONTRACT.md` section 8: 1 = restore, 0 = new, null = no anchor call.
 * [WalletInitMode]'s ONLY effect on provisioning (DECISIONS.md D11) - no init-mode state persists
 * or alters any later behavior; `is_recovering` (DB-derived) is the durable restore signal.
 */
internal fun resolveIntent(mode: WalletInitMode): Int? =
    when (mode) {
        WalletInitMode.RestoreWallet -> 1
        WalletInitMode.NewWallet -> 0
        WalletInitMode.ExistingWallet -> null
    }

/**
 * The height of the newest bundled checkpoint for [network] - the offline lower bound the
 * `restoreAnchor` native falls back to when the server is unreachable (`FFI_JNI_CONTRACT.md`
 * section 8). Ports `SDK_ADAPTER_PLAN.md` Appendix Z item 3: `CheckpointTool` is `internal object`,
 * directly callable from this module; `loadLast(context, network)` is `loadNearest(context,
 * network, birthdayHeight = null)`, which its own KDoc documents as "load the most recent
 * checkpoint available".
 */
internal suspend fun newestBundledCheckpointHeight(
    context: Context,
    network: ZcashNetwork
): Long = CheckpointTool.loadLast(context, network).height.value

/**
 * The idempotency guard `DerivedDataDb.new` runs before calling `RustBackend.createAccount`
 * (`DerivedDataDb.kt` ~150: `setup != null && backend.getAccounts().isEmpty()`) - mirrored here as a
 * pure predicate so `SlipstreamSynchronizer.Companion.newLocked`'s fresh-wallet account-creation fix
 * (nothing else in that factory ever wrote an `accounts` row) can unit-test its decision table
 * without a JNI [cash.z.ecc.android.sdk.internal.Backend] or Android `Context`. [hasSetup] is
 * `setup != null`; a non-null setup only ever accompanies [WalletInitMode.NewWallet]/
 * [WalletInitMode.RestoreWallet] (the UFVK derivation in `newLocked` already requires it), so this
 * predicate is what makes an `ExistingWallet` relaunch of an already-provisioned DB a no-op instead
 * of writing a second account row.
 */
internal fun shouldCreateAccount(
    hasSetup: Boolean,
    accountsAreEmpty: Boolean
): Boolean = hasSetup && accountsAreEmpty

/**
 * The `restoreAnchor` native call ([com.zodl.slipstream.SlipstreamNative.restoreAnchor]) as an
 * injectable collaborator, mirroring the iOS twin's `Initializer.slipstreamAnchorSource` closure.
 * The endpoint, network id and engine Tor directory are captured by the implementation, so the
 * deferred-preparation tail only supplies the three facts that vary per [WalletInitMode] intent.
 * Being a seam is the point: it keeps the JNI binding out of the tail, so the tail is unit-testable
 * without `mockStatic`.
 */
internal fun interface SlipstreamAnchorSource {
    suspend operator fun invoke(
        intent: Int,
        birthdayHeight: Long,
        fallbackCheckpointHeight: Long
    ): SlipstreamRestoreAnchor
}

/**
 * Everything `SlipstreamSynchronizer.Companion.newLocked` defers out of construction into the
 * synchronizer's own preparation job: the heavy tail (anchor resolution, data-DB provisioning,
 * `engine.open`/`engine.start`) that used to run before `new()` returned.
 *
 * Every JNI or assets touch the tail performs sits behind one of the lambdas here -
 * [anchorSource] for `restoreAnchor`, [fallbackCheckpointHeight] for
 * [newestBundledCheckpointHeight], [treeState]/[lastCheckpointTreeState] for
 * [CheckpointTool], [dbWalletSummary] for the wallet DB's own summary read. [totalMemoryBytes] is a
 * plain value rather than a lambda because the `ActivityManager` read that produces it is cheap
 * enough to stay on the construction path.
 *
 * [requestedBirthday] is the caller's birthday, already validated cheap-side (non-null whenever
 * [walletInitMode] is [WalletInitMode.RestoreWallet]), and [ufvk] the key derived cheap-side - a
 * caller bug must still throw synchronously out of `new()` rather than becoming an asynchronous
 * setup error.
 *
 * [setup] carries the seed the tail needs for `initDataDb` and `createAccount`, which run well after
 * `new()` has returned. The synchronizer owns that seed: `Companion.new` hands over a copy of the
 * caller's (see [copyOwningSeed]), so a caller that wipes its own seed as soon as `new()` returns
 * cannot leave the tail creating the account from zeros, and the synchronizer calls [releaseSetup]
 * once preparation has settled, however it settled.
 *
 * @property dbWalletSummary the balance seed the tail publishes at `DbReady`, read straight from the
 * wallet database so an existing wallet renders its real balances in the same phase its account row
 * surfaces. `null` means no summary is available yet - a fresh or never-scanned database - and skips
 * the seed, leaving the host on its shimmer until the engine's first tick.
 * @property exactBirthdayTreeState set only for a [WalletInitMode.RestoreWallet] asked to start
 * exactly at [requestedBirthday]: fetches the tree state at `requestedBirthday - 1` (see
 * [fetchExactBirthdayTreeState]), or answers `null` when it is not available, so that the account
 * is created from the bundled checkpoint instead. Set, it also has the preparation resolve the anchor and
 * this tree state while the data DB is initialized, and read the bundled checkpoint only if the account is
 * created from it. `null` keeps the checkpoint start and the preparation's order: data DB first, then the
 * anchor, then the bundled checkpoint, as the main wallet's.
 * @property birthdayResolved completed with whether the account was created from the exact tree state: by
 * preparation once the account is created, and with `false` by the end of preparation in case it never got that
 * far. The first completion wins.
 */
@Suppress("LongParameterList")
internal class PrepareInputs(
    val walletInitMode: WalletInitMode,
    val requestedBirthday: BlockHeight?,
    setup: AccountCreateSetup?,
    val ufvk: String?,
    val anchorSource: SlipstreamAnchorSource,
    val fallbackCheckpointHeight: suspend () -> Long,
    val treeState: suspend (BlockHeight?) -> TreeState,
    val lastCheckpointTreeState: suspend () -> TreeState,
    val dbWalletSummary: suspend () -> WalletSummary?,
    val totalMemoryBytes: Long,
    val exactBirthdayTreeState: (suspend (BlockHeight) -> TreeState?)? = null,
    val birthdayResolved: CompletableDeferred<Boolean>? = null
) {
    /** Whether these inputs were given a [setup], which stays known after [releaseSetup]. */
    val hasSetup: Boolean = setup != null

    /** The account setup, with its seed; `null` once [releaseSetup] ran, or when none was given. */
    @Volatile
    var setup: AccountCreateSetup? = setup
        private set

    /**
     * Overwrites the seed of [setup] with zeros and drops it. Called once preparation has settled,
     * when nothing reads it any more. Idempotent.
     */
    fun releaseSetup() {
        setup?.seed?.byteArray?.fill(0)
        setup = null
    }
}

/**
 * This setup with a copy of its seed, for a holder that must keep the seed beyond the caller's
 * control and wipe it itself (see [PrepareInputs.releaseSetup]). The caller's array is left as it is.
 */
internal fun AccountCreateSetup.copyOwningSeed(): AccountCreateSetup =
    copy(seed = FirstClassByteArray(seed.byteArray.copyOf()))

/**
 * How long [fetchExactBirthdayTreeState] waits for the server before the wallet starts at the
 * bundled checkpoint instead: longer than a fetch over a fresh Tor circuit takes, and short enough
 * that giving up still costs less than the scan from the checkpoint it would have saved.
 */
internal val EXACT_BIRTHDAY_TREE_STATE_TIMEOUT: Duration = 30.seconds

/**
 * Fetches the tree state at `birthday - 1` from [walletClient], which seeds an account whose
 * birthday is exactly [birthday] (the backend sets the birthday to the tree state's height plus
 * one), as `Synchronizer.new` does for an exact birthday. With Tor enabled in [sdkFlags] the request
 * goes over a fresh Tor circuit ([ServiceMode.UniqueTor]), which never falls back to a direct
 * connection; without Tor it goes directly to the server, as the rest of the wallet's traffic does.
 *
 * Returns `null`, after logging, when the server does not answer within [timeout], fails, or answers
 * with an empty tree state, and for a birthday at the genesis block, below which there is no tree
 * state, so that the caller starts at the bundled checkpoint instead.
 */
internal suspend fun fetchExactBirthdayTreeState(
    walletClient: CombinedWalletClient,
    sdkFlags: SdkFlags,
    birthday: BlockHeight,
    timeout: Duration = EXACT_BIRTHDAY_TREE_STATE_TIMEOUT
): TreeState? {
    if (birthday.value < 1) return null
    val treeStateHeight = BlockHeightUnsafe(birthday.value - 1)
    val fetched =
        runCatchingCancellable {
            withTimeoutOrNull(timeout) {
                walletClient.getTreeState(treeStateHeight, sdkFlags ifTor ServiceMode.UniqueTor)
            }
        }
    val response = fetched.getOrNull()
    val encoded = (response as? Response.Success<TreeStateUnsafe>)?.result?.encoded?.takeIf { it.isNotEmpty() }
    return if (encoded == null) {
        val reason =
            fetched.exceptionOrNull()?.let { "failed (${it::class.simpleName})" }
                ?: when (response) {
                    null -> "timed out after $timeout"
                    is Response.Failure -> "failed (${response::class.simpleName})"
                    else -> "got an empty tree state"
                }
        Twig.warn { "Tree state fetch for the exact birthday $reason; using the checkpoint" }
        null
    } else {
        Twig.info { "Restore: using tree state at height ${treeStateHeight.value} for exact birthday" }
        TreeState(encoded)
    }
}
