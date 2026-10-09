package cash.z.ecc.android.sdk.internal.ledger

import cash.z.ecc.android.sdk.exception.LedgerException
import cash.z.ecc.android.sdk.internal.Twig
import cash.z.ecc.android.sdk.ledger.LedgerAccountBinding
import cash.z.ecc.android.sdk.ledger.LedgerApduTransport
import cash.z.ecc.android.sdk.ledger.LedgerSigningProgress
import cash.z.ecc.android.sdk.model.AccountUuid
import cash.z.ecc.android.sdk.model.Pczt
import cash.z.ecc.android.sdk.model.ZcashNetwork
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

/**
 * The signing ceremony runner: the coroutine equivalent of `pczt_ledger`'s blocking
 * `ceremony::sign`, with the same retry, deadline and teardown rules.
 *
 * - The device's `GET_FIRMWARE_VERSION` reply is read first (resent on `0x6901`), and the session is
 *   built from it; the session's own first command re-reads the version and refuses a device that
 *   answers differently.
 * - Each command waits the engine's normal timeout, except from the review packet to the end of the
 *   session, where no timeout applies: the device answers once the user has decided.
 * - A `0x6901` refusal is answered by resending the same command after the engine's backoff.
 * - A failed exchange closes the transport (see [LedgerExchanger]); cancellation closes it too. A
 *   refusal by the device leaves it open: the channel is still in step, and a restartable failure can
 *   be retried with a fresh ceremony over the same transport.
 * - Cancellation is checked before each command and before the signatures are applied, so a
 *   cancellation that lands between two exchanges stops the ceremony before the next command, even
 *   over a transport that does not check cancellation itself.
 * - The whole ceremony holds the transport ([LedgerCeremony]): a `LedgerDevice` call or an app
 *   switch over the same transport waits for it to end instead of putting a command inside it, and a
 *   ceremony cancelled while it waits for its own turn closes nothing, since it never sent anything.
 */
internal class LedgerPcztSigner(
    private val backend: TypesafeLedgerBackend
) {
    @Suppress("LongParameterList")
    suspend fun sign(
        dataDbFile: File,
        network: ZcashNetwork,
        pczt: Pczt,
        accountUuid: AccountUuid,
        binding: LedgerAccountBinding,
        transport: LedgerApduTransport,
        onProgress: ((LedgerSigningProgress) -> Unit)?
    ): Pczt =
        LedgerCeremony.run(transport) {
            signHoldingTransport(dataDbFile, network, pczt, accountUuid, binding, transport, onProgress)
        }

    /** The ceremony itself, run while [sign] holds [transport] for it. */
    @Suppress("LongParameterList", "ThrowsCount")
    private suspend fun signHoldingTransport(
        dataDbFile: File,
        network: ZcashNetwork,
        pczt: Pczt,
        accountUuid: AccountUuid,
        binding: LedgerAccountBinding,
        transport: LedgerApduTransport,
        onProgress: ((LedgerSigningProgress) -> Unit)?
    ): Pczt {
        val policy = backend.policy
        val exchanger = LedgerExchanger(transport, policy)
        val progress = ProgressReporter(onProgress)
        try {
            progress.report(LedgerSigningProgress.IdentifyingDevice)
            val firmwareVersionReply =
                exchanger.query(backend.firmwareVersionApdu(), policy.normalTimeout) { reply ->
                    backend.parseAppVersion(reply)
                    reply.copyOf()
                }
            val session =
                try {
                    backend.newSignSession(
                        dataDbFile = dataDbFile,
                        network = network,
                        accountUuid = accountUuid,
                        pczt = pczt,
                        deviceIdentity = binding.deviceIdentity,
                        zip32AccountIndex = binding.zip32AccountIndex,
                        firmwareVersionReply = firmwareVersionReply
                    )
                } finally {
                    firmwareVersionReply.fill(0)
                }
            return session.use {
                pump(it, exchanger, policy, progress)
                currentCoroutineContext().ensureActive()
                val signed = it.finish()
                progress.report(LedgerSigningProgress.Complete)
                signed
            }
        } catch (e: CancellationException) {
            exchanger.closeQuietly()
            throw e
        } catch (e: LedgerException) {
            Twig.info { "Ledger signing failed: ${e.javaClass.simpleName}, restartable=${e.isRestartable}" }
            throw e
        }
    }

    private suspend fun pump(
        session: LedgerSignSession,
        exchanger: LedgerExchanger,
        policy: LedgerPolicy,
        progress: ProgressReporter
    ) {
        val total = session.totalCommands
        var sent = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            when (val step = session.nextStep()) {
                is SignStep.Send -> {
                    progress.report(progressFor(step, session.stage(), sent, total))
                    val timeout = if (step.waitsForUser) null else policy.normalTimeout
                    val reply =
                        try {
                            exchanger.exchange(step.apdu, timeout)
                        } finally {
                            step.apdu.fill(0)
                        }
                    val status =
                        try {
                            session.processResponse(reply)
                        } finally {
                            reply.fill(0)
                        }
                    when (status) {
                        SignStatus.RETRY_SAME_APDU -> delay(policy.cmdNotAcceptedBackoff)
                        SignStatus.COMPLETE -> return
                        SignStatus.MORE_APDUS, SignStatus.AWAITING_USER_ACTION -> sent++
                    }
                }

                // A command is outstanding whose reply was never processed; nothing can resume it.
                SignStep.AwaitingReply -> {
                    throw LedgerException.Internal(null)
                }

                SignStep.Done -> {
                    if (session.stage() == SignStage.COMPLETE) {
                        return
                    } else {
                        throw LedgerException.Internal(null)
                    }
                }
            }
        }
    }

    private fun progressFor(
        step: SignStep.Send,
        stage: SignStage,
        sent: Int,
        total: Int
    ): LedgerSigningProgress =
        when {
            step.announcesReview -> LedgerSigningProgress.AwaitingReviewOnDevice
            stage == SignStage.IDENTIFYING_DEVICE -> LedgerSigningProgress.IdentifyingDevice
            stage == SignStage.STREAMING -> LedgerSigningProgress.Streaming(sent, total)
            else -> LedgerSigningProgress.Signing
        }

    /**
     * Reports each distinct progress value once, in order.
     */
    private class ProgressReporter(
        private val onProgress: ((LedgerSigningProgress) -> Unit)?
    ) {
        private var last: LedgerSigningProgress? = null

        fun report(progress: LedgerSigningProgress) {
            if (progress != last) {
                last = progress
                onProgress?.invoke(progress)
            }
        }
    }
}
