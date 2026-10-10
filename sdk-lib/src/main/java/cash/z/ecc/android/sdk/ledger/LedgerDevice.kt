package cash.z.ecc.android.sdk.ledger

import cash.z.ecc.android.sdk.exception.LedgerException
import cash.z.ecc.android.sdk.exception.isLinkFailure
import cash.z.ecc.android.sdk.internal.Twig
import cash.z.ecc.android.sdk.internal.ledger.LedgerCeremony
import cash.z.ecc.android.sdk.internal.ledger.LedgerCeremonyHold
import cash.z.ecc.android.sdk.internal.ledger.LedgerExchanger
import cash.z.ecc.android.sdk.internal.ledger.TypesafeLedgerBackend
import cash.z.ecc.android.sdk.internal.ledger.TypesafeLedgerBackendImpl
import cash.z.ecc.android.sdk.internal.ledger.UfvkExportStep
import cash.z.ecc.android.sdk.model.Account
import cash.z.ecc.android.sdk.model.UnifiedFullViewingKey
import cash.z.ecc.android.sdk.model.ZcashNetwork
import cash.z.ecc.android.sdk.model.Zip32AccountIndex
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException
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
 * A call that is cancelled once it runs closes the transport, whatever step it was at: the device can
 * be in the middle of a command or of a multi-step exchange, so open a new connection before you try
 * again. A call that is cancelled while it waits for another call on this device, or for another
 * ceremony on its transport, to finish closes nothing, because it never reached the device; neither
 * does one whose transport refused the exchange before sending anything
 * ([LedgerExchangeNotStartedException]).
 *
 * The call holding the device also holds its transport for all of its commands, and so does every
 * other ceremony the SDK runs over a transport (another `LedgerDevice` over the same transport, a
 * signing ceremony, an app switch): a call made while any of them is in progress waits for it, so no
 * two of them interleave their commands on the device. A call that reconnects mid-flight — see
 * [pairAccount]'s `reconnect` parameter — holds its replacement transport the same way for the rest
 * of the call. See `LedgerCeremony`.
 *
 * Nothing this class handles is logged: not the commands, not the replies, not the keys, addresses or
 * identities they carry.
 *
 * # Transport ownership
 *
 * The caller owns every transport: the one passed to [new] and every one a `reconnect` function
 * given to [pairAccount] returns. The SDK closes a transport whose exchange failed or whose call
 * was cancelled (closing is idempotent) but never closes one otherwise, so the caller closes each
 * transport it opened once it is done, whether the call succeeded or failed. [transport] is the one
 * this device currently talks over; after [pairAccount] reconnected, it is the reconnected one, and
 * later calls use it.
 *
 * Nothing else changes [transport]. [LedgerZcashApp.ensureZcashAppOpen] may close the transport it
 * is given and return another, so call it before building a device, or build a new device over the
 * transport it returns: a device whose [transport] was passed to it stays bound to that transport,
 * which may be closed.
 */
class LedgerDevice internal constructor(
    transport: LedgerApduTransport,
    private val network: ZcashNetwork,
    private val backend: TypesafeLedgerBackend
) {
    /**
     * Serializes every call on this one device instance, taken before the transport's own ceremony
     * gate and held for as long as the call runs. The order is always this mutex first, then a
     * ceremony gate: `LedgerPcztSigner` and `LedgerZcashApp` take only a ceremony gate and never this
     * one, so the two are never waited on in reverse order, and acquiring them in this one, fixed
     * order cannot deadlock.
     */
    private val mutex = Mutex()
    private var exchanger = LedgerExchanger(transport, backend.policy)

    /**
     * The running call's hold on a transport its `reconnect` returned, released when the call ends;
     * `null` while the call is on the transport `holdingDevice` took. One call runs at a time, so one
     * field is enough.
     */
    private var replacementHold: LedgerCeremonyHold? = null

    /**
     * The transport this device talks over: the one passed to [new], or the last one a
     * [pairAccount] `reconnect` function returned. The caller closes it; see the class
     * documentation. Readable from any thread. A transport [LedgerZcashApp.ensureZcashAppOpen]
     * returns never replaces it; build a new device over that one.
     */
    @Volatile
    var transport: LedgerApduTransport = transport
        private set

    /**
     * Reads the version of the Zcash app running on the device.
     *
     * @throws LedgerException.WrongApp if the Zcash app is not open.
     * @throws LedgerException for any other failure.
     */
    suspend fun appVersion(): LedgerAppVersion = holdingDevice { readAppVersion() }

    /**
     * Reads the device's identity on this network. Silent: nothing is shown on the device.
     *
     * @throws LedgerException for any failure.
     */
    suspend fun deviceIdentity(): LedgerDeviceIdentity = holdingDevice { readDeviceIdentity() }

    /**
     * Pairs the device's ZIP 32 account [zip32AccountIndex]: exports its unified full viewing key,
     * which the user approves on the device, and binds it to the device's identity.
     *
     * The identity is read once, before the export. The Zcash app leaves a status screen up after
     * the export and drops the next command until the user dismisses it, so no identity is read
     * after the export.
     *
     * For account 0, the SDK checks that the exported key belongs to the device that answered the
     * identity read. The identity is a hash of the public key at `m/44'/coin'/0'/0/0`, which is the
     * first external transparent address key of account 0. The SDK derives that key from the
     * exported key's transparent component and compares the two. If they differ, or if the exported
     * key has no transparent component (the Zcash app always exports one), the SDK discards the key.
     * This check binds the key to the identity, not to the hardware: the identity read is not a
     * challenge, so a link that also replaces the identity reply passes it. The user's comparison of
     * the address on the device's screen is the check against such a link.
     *
     * For every other account, the exported key carries no key on that path, so the SDK cannot
     * check it against the identity. It checks only that the key decodes for this network.
     *
     * To check any account against a link that replaces the device's replies, show the user the
     * address that [expectedUnifiedAddress] derives from the returned key, and call
     * [displayUnifiedAddress] with that key. The user compares the address on the device's screen
     * with the one the app shows, and the SDK refuses a device that replies with another address.
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
     * A transport [reconnect] returns becomes this device's [transport] as soon as this call holds its
     * ceremony, whatever the outcome from then on, and belongs to the caller like the one passed to
     * [new]; an exception [reconnect] throws propagates as it is. The one case in which it is not
     * adopted: another ceremony already holds it (see `LedgerCeremony`) and this call is cancelled
     * while it waits for its turn on it. [transport] then stays the original, the replacement stays
     * open with the ceremony holding it, and this call closes nothing of theirs.
     *
     * If the calling coroutine is cancelled while the pairing runs, the pairing sends nothing more
     * and closes [transport], also between two chunks of the export. The device can be left in the
     * middle of the export, so open a new connection before you try again.
     *
     * Import the returned key with [Account.LEDGER_KEY_SOURCE] as its key source, and persist the
     * binding next to the imported account; see [LedgerAccountPairing].
     *
     * @param readTimeout How long each read before the export may take; the engine's normal timeout
     *        by default. Pass [DEFAULT_PAIRING_READ_TIMEOUT] to detect a stalled device early.
     * @param reconnect Opens a fresh connection to the same device, for one retry of the reads
     *        before the export; `null` for no retry. It runs while this device holds its lock and the
     *        original transport's ceremony gate, neither of which is reentrant: it must not call this
     *        device, nor run any other ceremony over this device's [transport], directly or through
     *        anything that waits on one, or it suspends forever. The transport it returns is normally
     *        a new one, not under that ceremony gate while [reconnect] itself runs; this call then
     *        takes that transport's own ceremony gate too, adopts it as [transport] once it holds it,
     *        and keeps it for everything that follows — the retried reads, the export and the identity
     *        check — so the replacement is held for the rest of the call exactly as the original
     *        transport was. A [reconnect] that hands back this device's own [transport] (one that
     *        reconnects internally) is accepted too; that one is already held.
     * @throws LedgerException.AppTooOld if the Zcash app cannot sign PCZTs, before anything is
     *         exported.
     * @throws LedgerException.UserRejected if the user declines the export.
     * @throws LedgerException.DeviceMismatch if [zip32AccountIndex] is 0 and the exported key does
     *         not belong to the device that answered the identity read.
     * @throws LedgerException.MalformedReply if the exported key does not decode, or if
     *         [zip32AccountIndex] is 0 and the key has no transparent component.
     * @throws LedgerException.DerivationBudgetExhausted if the user has to reopen the Zcash app.
     * @throws LedgerException for any other failure.
     */
    suspend fun pairAccount(
        zip32AccountIndex: Zip32AccountIndex,
        readTimeout: Duration = backend.policy.normalTimeout,
        reconnect: (suspend () -> LedgerApduTransport)? = null
    ): LedgerAccountPairing =
        holdingDevice {
            val (version, identity) = readBeforeExport(readTimeout, reconnect)
            val ufvk = exportUfvk(zip32AccountIndex)
            if (zip32AccountIndex.index == IDENTITY_ACCOUNT_INDEX) {
                backend.checkUfvkDeviceIdentity(network, ufvk, identity)
            }
            LedgerAccountPairing(
                ufvk = ufvk,
                binding = LedgerAccountBinding(identity, zip32AccountIndex),
                appVersion = version
            )
        }

    /**
     * Shows the account's unified address on the device for the user to compare, and checks the
     * device's reply against the address the SDK derives from [ufvk].
     *
     * The device shows a unified address that carries only the account's Orchard receiver at
     * diversifier index 0, whatever [transparentAddressIndex] is. [transparentAddressIndex] selects
     * only the transparent address the device shows next to it on its screen.
     *
     * Before you call this function, show the user the address that [expectedUnifiedAddress]
     * derives from [ufvk], and ask the user to compare it with the address on the device's screen.
     * Do not show the address that the device replies with: a compromised link can replace the
     * reply, but not the device's screen. The SDK compares the reply with the derived address after
     * the user confirms, and refuses a reply that differs.
     *
     * @param ufvk The account's unified full viewing key, as [pairAccount] returned it.
     * @param zip32AccountIndex The ZIP 32 account index of [ufvk] on the device.
     * @param transparentAddressIndex The external-chain transparent address index to show, at most
     *        50000.
     * @return The address the device showed, which is equal to the one [expectedUnifiedAddress]
     *         derives from [ufvk].
     * @throws LedgerException.DeviceMismatch if the device replies with another address: the
     *         device does not hold [ufvk] at [zip32AccountIndex], or the link replaced the reply.
     * @throws LedgerException.UserRejected if the user rejects the address on the device.
     * @throws LedgerException.InvalidInput if [ufvk] does not decode for this network or has no
     *         Orchard component, before anything is sent, or if an index is outside what the device
     *         accepts.
     * @throws LedgerException for any other failure.
     */
    suspend fun displayUnifiedAddress(
        ufvk: UnifiedFullViewingKey,
        zip32AccountIndex: Zip32AccountIndex,
        transparentAddressIndex: Long = 0
    ): String =
        holdingDevice {
            val expected = backend.expectedUnifiedAddress(ufvk, network)
            val shown =
                exchanger.query(
                    apdu =
                        backend.unifiedAddressApdu(
                            network,
                            zip32AccountIndex,
                            transparentAddressIndex,
                            display = true
                        ),
                    // The reply waits for the user to confirm the address on screen.
                    timeout = null
                ) { reply -> backend.parseUnifiedAddress(reply, network) }
            if (shown != expected) {
                throw LedgerException.DeviceMismatch()
            }
            expected
        }

    /**
     * Runs [block] once no other call on this device, and no other ceremony on its transport, runs. A
     * cancellation while [block] runs closes the transport the call is on — the reconnected one, once
     * [readBeforeExport] has moved [transport] on — because the device can be in the middle of a
     * command or an exchange; a cancellation while the call waits for its turn, on this device or at
     * the transport's ceremony gate, closes nothing.
     */
    private suspend fun <T> holdingDevice(block: suspend () -> T): T =
        mutex.withLock {
            LedgerCeremony.run(transport) {
                try {
                    block()
                } catch (e: LedgerExchangeNotStartedException) {
                    // The transport refused the exchange before sending anything: the device never
                    // saw it, so there is nothing on it to abandon, and the transport stays open for
                    // whoever is using it.
                    throw e
                } catch (e: CancellationException) {
                    exchanger.closeQuietly()
                    throw e
                } finally {
                    replacementHold?.release()
                    replacementHold = null
                }
            }
        }

    /**
     * The reads before the export, retried once over a transport from [reconnect] when the first
     * attempt fails on the connection.
     *
     * A retry takes the reconnected transport's ceremony ([LedgerCeremony.adopt]), nested inside the
     * ceremony [holdingDevice] already holds on the original one, and only then moves [transport] and
     * the exchanger to it, so a cancellation while another ceremony still holds the replacement
     * leaves them on the original, already closed, one, and [holdingDevice]'s cleanup closes nothing
     * of the holder's. The two are different transport instances, so the nesting cannot deadlock. A
     * [reconnect] that hands back this very transport — one that reconnects internally, whose `close`
     * only dropped the link — is already held and is not taken again. The hold lasts until the call
     * ends; [holdingDevice] releases it.
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
            val replacement = reconnect()
            if (replacement !== transport) {
                replacementHold = LedgerCeremony.adopt(replacement)
            }
            transport = replacement
            exchanger = LedgerExchanger(replacement, backend.policy)
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
                // A transport that does not check for cancellation itself returns at once, so check
                // here too: a cancelled pairing sends no further chunk.
                currentCoroutineContext().ensureActive()
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
        /** The ZIP 32 account whose transparent key the device identity hashes. */
        private const val IDENTITY_ACCOUNT_INDEX = 0L

        /**
         * A [pairAccount] `readTimeout` suited to a pairing: the reads before the export answer at
         * once on a healthy link, so a device silent this long is stalled. It is not the default,
         * which is the engine's normal timeout.
         */
        val DEFAULT_PAIRING_READ_TIMEOUT: Duration = 10.seconds

        /**
         * The unified address a Ledger device shows for the account whose viewing key is [ufvk]:
         * the account's Orchard receiver at diversifier index 0 of the external scope, alone in a
         * unified address, encoded as the Zcash app encodes it. It is not the account's full
         * unified address, which also carries a transparent receiver.
         *
         * Show this address to the user while [displayUnifiedAddress] shows the device's address,
         * for the user to compare the two. Loads the SDK's native library. Uses no device.
         *
         * @throws LedgerException.InvalidInput if [ufvk] does not decode for [network] or has no
         *         Orchard component.
         */
        suspend fun expectedUnifiedAddress(
            ufvk: UnifiedFullViewingKey,
            network: ZcashNetwork
        ): String = TypesafeLedgerBackendImpl.new().expectedUnifiedAddress(ufvk, network)

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
