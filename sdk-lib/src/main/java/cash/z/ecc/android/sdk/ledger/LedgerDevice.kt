package cash.z.ecc.android.sdk.ledger

import cash.z.ecc.android.sdk.exception.LedgerException
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
 */
class LedgerDevice internal constructor(
    private val transport: LedgerApduTransport,
    private val network: ZcashNetwork,
    private val backend: TypesafeLedgerBackend
) {
    private val mutex = Mutex()
    private val exchanger = LedgerExchanger(transport, backend.policy)

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
     * Import the returned key with [Account.LEDGER_KEY_SOURCE] as its key source, and persist the
     * binding next to the imported account; see [LedgerAccountPairing].
     *
     * @throws LedgerException.AppTooOld if the Zcash app cannot sign PCZTs, before anything is
     *         exported.
     * @throws LedgerException.UserRejected if the user declines the export.
     * @throws LedgerException.DerivationBudgetExhausted if the user has to reopen the Zcash app.
     * @throws LedgerException for any other failure.
     */
    suspend fun pairAccount(zip32AccountIndex: Zip32AccountIndex): LedgerAccountPairing =
        mutex.withLock {
            val version = readAppVersion()
            if (!version.supportsPczt) {
                throw LedgerException.AppTooOld(
                    reason =
                        "the Zcash app on the device is v${version.major}.${version.minor}.${version.patch}, " +
                            "which predates PCZT signing"
                )
            }
            val identity = readDeviceIdentity()
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

    private suspend fun readAppVersion(): LedgerAppVersion =
        exchanger.query(backend.firmwareVersionApdu(), backend.policy.normalTimeout) { reply ->
            backend.parseAppVersion(reply)
        }

    private suspend fun readDeviceIdentity(): LedgerDeviceIdentity =
        exchanger.query(backend.deviceIdentityApdu(network), backend.policy.normalTimeout) { reply ->
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
         * A device reached over [transport], for [network]. Loads the SDK's native library.
         */
        suspend fun new(
            transport: LedgerApduTransport,
            network: ZcashNetwork
        ): LedgerDevice = LedgerDevice(transport, network, TypesafeLedgerBackendImpl.new())
    }
}
