package com.zodl.slipstream.internal.spend

import cash.z.ecc.android.sdk.exception.TransactionEncoderException
import cash.z.ecc.android.sdk.internal.Backend
import cash.z.ecc.android.sdk.internal.jni.ProposalInsufficientFundsException
import cash.z.ecc.android.sdk.internal.model.ProposalUnsafe
import cash.z.ecc.android.sdk.model.Account
import cash.z.ecc.android.sdk.model.AccountUuid
import cash.z.ecc.android.sdk.model.FirstClassByteArray
import cash.z.ecc.android.sdk.model.MemoContent
import cash.z.ecc.android.sdk.model.Proposal
import cash.z.ecc.android.sdk.model.RecipientAddress
import cash.z.ecc.android.sdk.model.SdkFlags
import cash.z.ecc.android.sdk.model.UnifiedSpendingKey
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.lightwallet.client.CombinedWalletClient
import com.zodl.slipstream.internal.SlipstreamEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/**
 * [SlipstreamSpendService.proposeSendMax]: the account's whole spendable balance through the backend's own send-max
 * call, with the failure mapping the upstream `TransactionEncoderImpl.proposeSendMax` applies - the typed
 * [ProposalInsufficientFundsException] (or an insufficient-funds text) becomes
 * [TransactionEncoderException.InsufficientFundsException], anything else
 * [TransactionEncoderException.ProposalFromParametersException], and a cancellation stays itself.
 *
 * Conventions follow [SlipstreamSpendServiceErrorMappingTest]: plain Mockito with exact-argument stubs, JUnit4 and
 * `runBlocking`.
 */
class SlipstreamSpendServiceSendMaxTest {
    private val account = Account.new(AccountUuid.new(ByteArray(ACCOUNT_UUID_SIZE)))

    private val recipient =
        mock(RecipientAddress::class.java).also {
            `when`(it.encoding).thenReturn(RECIPIENT)
        }

    @Test
    fun proposesTheWholeSpendableBalanceToTheRecipient() =
        runBlocking<Unit> {
            val backend = mock(Backend::class.java)
            val proposalUnsafe = mock(ProposalUnsafe::class.java)
            `when`(proposalUnsafe.totalFeeRequired()).thenReturn(FEE)
            `when`(backend.proposeSendMaxTransfer(account.accountUuid.value, RECIPIENT, null))
                .thenReturn(proposalUnsafe)

            val proposal = service(backend).proposeSendMax(account, recipient, null)

            assertSame(proposalUnsafe, proposal.toUnsafe())
            assertEquals(Zatoshi(FEE), proposal.totalFeeRequired())
        }

    @Test
    fun passesTheMemoAsItsBytes() =
        runBlocking<Unit> {
            val backend = mock(Backend::class.java)
            val memo = MemoContent.fromString("Gift Card")
            val memoBytes = memo.asMemoBytes().bytes.byteArray
            val proposalUnsafe = mock(ProposalUnsafe::class.java)
            `when`(proposalUnsafe.totalFeeRequired()).thenReturn(FEE)
            `when`(backend.proposeSendMaxTransfer(account.accountUuid.value, RECIPIENT, memoBytes))
                .thenReturn(proposalUnsafe)

            val proposal = service(backend).proposeSendMax(account, recipient, memo)

            assertSame(proposalUnsafe, proposal.toUnsafe())
        }

    @Test
    fun anEmptyWalletIsInsufficientFunds() =
        runBlocking<Unit> {
            val backend = mock(Backend::class.java)
            val refusal = ProposalInsufficientFundsException("nothing spendable")
            `when`(backend.proposeSendMaxTransfer(account.accountUuid.value, RECIPIENT, null)).thenThrow(refusal)

            val exception =
                assertFailsWith<TransactionEncoderException.InsufficientFundsException> {
                    service(backend).proposeSendMax(account, recipient, null)
                }

            assertSame(refusal, exception.rootCause)
        }

    @Test
    fun aBalanceBelowTheFeeReportedAsTextIsInsufficientFunds() =
        runBlocking<Unit> {
            val backend = mock(Backend::class.java)
            val refusal = RuntimeException("Insufficient balance (have 5000, need 10000 including fee)")
            `when`(backend.proposeSendMaxTransfer(account.accountUuid.value, RECIPIENT, null)).thenThrow(refusal)

            val exception =
                assertFailsWith<TransactionEncoderException.InsufficientFundsException> {
                    service(backend).proposeSendMax(account, recipient, null)
                }

            assertSame(refusal, exception.rootCause)
        }

    @Test
    fun anyOtherFailureIsAParametersProposalFailure() =
        runBlocking<Unit> {
            val backend = mock(Backend::class.java)
            val failure = RuntimeException("the database is locked")
            `when`(backend.proposeSendMaxTransfer(account.accountUuid.value, RECIPIENT, null)).thenThrow(failure)

            val exception =
                assertFailsWith<TransactionEncoderException.ProposalFromParametersException> {
                    service(backend).proposeSendMax(account, recipient, null)
                }

            assertSame(failure, exception.rootCause)
        }

    @Test
    fun aCancellationTravelsAsItself() =
        runBlocking<Unit> {
            val backend = mock(Backend::class.java)
            val cancellation = CancellationException("cancelled")
            `when`(backend.proposeSendMaxTransfer(account.accountUuid.value, RECIPIENT, null)).thenThrow(cancellation)

            val thrown =
                assertFailsWith<CancellationException> {
                    service(backend).proposeSendMax(account, recipient, null)
                }

            assertSame(cancellation, thrown)
        }

    @Test
    fun proposingNeverTouchesTheEngineOrTheSaplingParameters() =
        runBlocking<Unit> {
            val backend = mock(Backend::class.java)
            val engine = mock(SlipstreamEngine::class.java)
            val proposalUnsafe = mock(ProposalUnsafe::class.java)
            `when`(proposalUnsafe.totalFeeRequired()).thenReturn(FEE)
            `when`(backend.proposeSendMaxTransfer(account.accountUuid.value, RECIPIENT, null))
                .thenReturn(proposalUnsafe)
            var ensured = false

            service(backend, engine) { ensured = true }.proposeSendMax(account, recipient, null)

            verifyNoInteractions(engine)
            assertEquals(false, ensured)
        }

    @Test
    fun sendingAnOrchardOnlyProposalNeverFetchesTheSaplingParameters() =
        runBlocking<Unit> {
            val backend = mock(Backend::class.java)
            val proposalUnsafe = mock(ProposalUnsafe::class.java)
            `when`(proposalUnsafe.totalFeeRequired()).thenReturn(FEE)
            val proposal = Proposal.fromUnsafe(proposalUnsafe)
            val usk = mock(UnifiedSpendingKey::class.java)
            val uskBytes = byteArrayOf(1, 2, 3)
            `when`(usk.copyBytes()).thenReturn(uskBytes)
            `when`(backend.proposalRequiresSaplingProofs(proposalUnsafe)).thenReturn(false)
            `when`(backend.createProposedTransactions(proposalUnsafe, uskBytes)).thenReturn(emptyList())
            var ensured = 0

            service(backend) { ensured++ }.createProposedTransactions(proposal, usk).toList()

            assertEquals(0, ensured)
        }

    private fun service(
        backend: Backend,
        engine: SlipstreamEngine = mock(SlipstreamEngine::class.java),
        ensureSaplingParams: suspend () -> Unit = {}
    ): SlipstreamSpendService =
        SlipstreamSpendService(
            backend = backend,
            walletClient = mock(CombinedWalletClient::class.java),
            engine = engine,
            sdkFlags = SdkFlags(isTorEnabled = false, isExchangeRateEnabled = false),
            ensureSaplingParams = ensureSaplingParams,
            readRawTransaction = { FirstClassByteArray(byteArrayOf()) }
        )

    private companion object {
        const val ACCOUNT_UUID_SIZE = 16
        const val RECIPIENT = "u1recipient"
        const val FEE = 10_000L
    }
}
