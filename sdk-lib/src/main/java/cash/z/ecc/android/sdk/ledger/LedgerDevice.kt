package cash.z.ecc.android.sdk.ledger

import cash.z.ecc.android.sdk.exception.LedgerException
import cash.z.ecc.android.sdk.exception.isLinkFailure
import cash.z.ecc.android.sdk.internal.Twig
import cash.z.ecc.android.sdk.internal.ledger.LedgerExchanger
import cash.z.ecc.android.sdk.internal.ledger.TypesafeLedgerBackend
import cash.z.ecc.android.sdk.internal.ledger.TypesafeLedgerBackendImpl
import cash.z.ecc.android.sdk.internal.ledger.UfvkExportStep
import cash.z.ecc.android.sdk.model.Account
import cash.z.ecc.android.sdk.model.UnifiedFullViewingKey
import cash.z.ecc.android.sdk.model.ZcashNetwork
import cash.z.ecc.android.sdk.model.Zip32AccountIndex
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A Ledger device running the Zcash app, reached over [transport], for the commands that are not a
 * signing ceremony: reading its app version and identity, pairing an account, and showing an
 * address on its screen. Signing is `Synchronizer.signPcztWithLedger`.
 *
 * Operations run one at a time. Every command the device refuses with `0x6901` (its SDK refusing a
 * frame while a screen is up) is resent after a short backoff, up to the engine's retry budget. An
 * exchange that fails on the transport — a timeout, a disconnect — closes the transport, which then
 * has to be reopened; a refusal by the device leaves it open.
 *
 * Nothing this class handles is logged: not the commands, not the replies, not the keys, addresses or
 * identities they carry.
 *
 * # Transport ownership
 *
 * The caller owns every transport: the one passed to [new] and every one a `reconnect` function
 * given to [pairAccount] returns. The SDK closes a transport whose exchange failed (closing is
 * idempotent) but never closes one otherwise, so the caller closes each transport it opened once it
 * is done, whether the call succeeded or failed. [transport] is the one this device currently talks
 * over; after [pairAccount] reconnected, it is the reconnected one, and later calls use it.
 */
class LedgerDevice internal constructor(
    transport: LedgerApduTransport,
    private val network: ZcashNetwork,
    private val backend: TypesafeLedgerBackend
) {
    private val mutex = Mutex()
    private var exchanger = LedgerExchanger(transport, backend.policy)

    /**
     * The transport this device talks over: the one passed to [new], or the last one a
     * [pairAccount] `reconnect` function returned. The caller closes it; see the class
     * documentation.
     */
    var transport: LedgerApduTransport = transport
        private set

    /**
     * Reads the version of the Zcash app running on the device.
     *
     * @throws LedgerException.WrongApp if the Zcash app is not open.
     * @throws LedgerException for any other failure.
     */
    suspend fun appVersion(): LedgerAppVersion = mutex.withLock { readAppVersion() }

    /**
     * Reads the device's identity on this network. Silent: nothing is shown on the device.
     *
     * @throws LedgerException for any failure.
     */
    suspend fun deviceIdentity(): LedgerDeviceIdentity = mutex.withLock { readDeviceIdentity() }

    /**
     * Pairs the device's ZIP 32 account [zip32AccountIndex]: exports its unified full viewing key,
     * which the user approves on the device, and binds it to the device's identity.
     *
     * The identity is read once, before the export. The Zcash app leaves a status screen up after
     * the export and drops the next command until the user dismisses it, and a transport speaks to
     * exactly one peripheral, so the device that answered the probe is the device that exported
     * the key.
     *
     * The reads before the export (the app version and the device's identity) each have
     * [readTimeout] to answer. Nothing waits on the user there, so a device that does not answer
     * within [DEFAULT_PAIRING_READ_TIMEOUT] is stalled; the default is still the engine's normal
     * timeout, as for every other read. When one of them fails on the connection —
     * [LedgerException.Timeout], [LedgerException.Disconnected], [LedgerException.ConnectionFailed]
     * or [LedgerException.DeviceNotFound] — and [reconnect] is given, the failed transport is closed,
     * [reconnect] is called once for a fresh one, and both reads are asked again over it before
     * anything else is sent. A second failure propagates. Once the export command has been sent,
     * nothing is retried: the export waits on the user with no timeout, its continuation keeps the
     * engine's normal timeout, and nothing is read after it.
     *
     * A transport [reconnect] returns becomes this device's [transport], whatever the outcome, and
     * belongs to the caller like the one passed to [new]; an exception [reconnect] throws propagates
     * as it is.
     *
     * Import the returned key with [Account.LEDGER_KEY_SOURCE] as its key source, and persist the
     * binding next to the imported account; see [LedgerAccountPairing].
     *
     * @param readTimeout How long each read before the export may take; the engine's normal timeout
     *        by default. Pass [DEFAULT_PAIRING_READ_TIMEOUT] to detect a stalled device early.
     * @param reconnect Opens a fresh connection to the same device, for one retry of the reads
     *        before the export; `null` for no retry. It runs while this device holds its lock, which
     *        is not reentrant: it must not call this device, directly or through anything that waits
     *        on it, or it suspends forever.
     * @throws LedgerException.AppTooOld if the Zcash app cannot sign PCZTs, before anything is
     *         exported.
     * @throws LedgerException.UserRejected if the user declines the export.
     * @throws LedgerException.DerivationBudgetExhausted if the user has to reopen the Zcash app.
     * @throws LedgerException for any other failure.
     */
    suspend fun pairAccount(
        zip32AccountIndex: Zip32AccountIndex,
        readTimeout: Duration = backend.policy.normalTimeout,
        reconnect: (suspend () -> LedgerApduTransport)? = null
    ): LedgerAccountPairing =
        mutex.withLock {
            val (version, identity) = readBeforeExport(readTimeout, reconnect)
            val ufvk = exportUfvk(zip32AccountIndex)
            LedgerAccountPairing(
                ufvk = ufvk,
                binding = LedgerAccountBinding(identity, zip32AccountIndex),
                appVersion = version
            )
        }

    /**
     * Shows the account's unified address on the device for the user to compare, and returns it
     * once the user confirms.
     *
     * The returned address carries only the account's Orchard receiver, at diversifier index 0,
     * whatever [transparentAddressIndex] is: the device encodes nothing else into it. Compare it
     * with a unified address built from the account's Orchard receiver at diversifier index 0
     * alone — the account's full unified address also carries a transparent receiver and never
     * matches. [transparentAddressIndex] selects the transparent address the device shows next to
     * it on screen.
     *
     * @param transparentAddressIndex The external-chain transparent address index to show, at most
     *        50000.
     * @throws LedgerException.UserRejected if the user rejects the address on the device.
     * @throws LedgerException.InvalidInput if an index is outside what the device accepts.
     * @throws LedgerException for any other failure.
     */
    suspend fun displayUnifiedAddress(
        zip32AccountIndex: Zip32AccountIndex,
        transparentAddressIndex: Long = 0
    ): String =
        mutex.withLock {
            exchanger.query(
                apdu = backend.unifiedAddressApdu(network, zip32AccountIndex, transparentAddressIndex, display = true),
                // The reply waits for the user to confirm the address on screen.
                timeout = null
            ) { reply -> backend.parseUnifiedAddress(reply, network) }
        }

    /**
     * The reads before the export, retried once over a transport from [reconnect] when the first
     * attempt fails on the connection.
     */
    private suspend fun readBeforeExport(
        readTimeout: Duration,
        reconnect: (suspend () -> LedgerApduTransport)?
    ): Pair<LedgerAppVersion, LedgerDeviceIdentity> =
        try {
            readAppVersionAndIdentity(readTimeout)
        } catch (e: LedgerException) {
            if (reconnect == null || !e.isLinkFailure()) {
                throw e
            }
            Twig.info { "Ledger read before the export failed (${e.javaClass.simpleName}); reconnecting once" }
            exchanger.closeQuietly()
            reconnect().also {
                transport = it
                exchanger = LedgerExchanger(it, backend.policy)
            }
            readAppVersionAndIdentity(readTimeout)
        }

    private suspend fun readAppVersionAndIdentity(readTimeout: Duration): Pair<LedgerAppVersion, LedgerDeviceIdentity> {
        val version = readAppVersion(readTimeout)
        if (!version.supportsPczt) {
            throw LedgerException.AppTooOld(
                reason =
                    "the Zcash app on the device is v${version.major}.${version.minor}.${version.patch}, " +
                        "which predates PCZT signing"
            )
        }
        return version to readDeviceIdentity(readTimeout)
    }

    private suspend fun readAppVersion(timeout: Duration = backend.policy.normalTimeout): LedgerAppVersion =
        exchanger.query(backend.firmwareVersionApdu(), timeout) { reply ->
            backend.parseAppVersion(reply)
        }

    private suspend fun readDeviceIdentity(timeout: Duration = backend.policy.normalTimeout): LedgerDeviceIdentity =
        exchanger.query(backend.deviceIdentityApdu(network), timeout) { reply ->
            backend.parseDeviceIdentity(reply)
        }

    /**
     * Runs the viewing-key export to completion.
     *
     * The loop has no retry cap of its own because the engine owns the export's state: a `0x6901`
     * refusal comes back as [UfvkExportStep.RetrySameApdu] at most `MAX_CMD_NOT_ACCEPTED_RETRIES`
     * times for one command, after which [UfvkExport.processResponse] throws
     * [LedgerException.DeviceRefused] and the export is over. Every other reply either advances the
     * export, completes it, or fails it. So every pass through the loop is one exchange the engine
     * asked for, and the engine's budget, not this loop, bounds how many there are.
     */
    private suspend fun exportUfvk(zip32AccountIndex: Zip32AccountIndex): UnifiedFullViewingKey {
        val export = backend.newUfvkExport(network, zip32AccountIndex)
        try {
            while (true) {
                // The export hands out nothing more only once it has completed, which returns below,
                // or failed, which has already thrown.
                val apdu = export.nextApdu() ?: throw LedgerException.Internal(null)
                val timeout = if (export.waitsForUser()) null else backend.policy.normalTimeout
                val reply =
                    try {
                        exchanger.exchange(apdu, timeout)
                    } finally {
                        apdu.fill(0)
                    }
                val step =
                    try {
                        export.processResponse(reply)
                    } finally {
                        reply.fill(0)
                    }
                when (step) {
                    UfvkExportStep.MoreChunks -> Unit
                    UfvkExportStep.RetrySameApdu -> delay(backend.policy.cmdNotAcceptedBackoff)
                    is UfvkExportStep.Complete -> return step.ufvk
                }
            }
        } finally {
            export.close()
        }
    }

    companion object {
        /**
         * A [pairAccount] `readTimeout` suited to a pairing: the reads before the export answer at
         * once on a healthy link, so a device silent this long is stalled. It is not the default,
         * which is the engine's normal timeout.
         */
        val DEFAULT_PAIRING_READ_TIMEOUT: Duration = 10.seconds

        /**
         * A device reached over [transport], for [network]. Loads the SDK's native library. The
         * caller keeps ownership of [transport]; see the class documentation.
         */
        suspend fun new(
            transport: LedgerApduTransport,
            network: ZcashNetwork
        ): LedgerDevice = LedgerDevice(transport, network, TypesafeLedgerBackendImpl.new())
    }
}
