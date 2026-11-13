package cash.z.ecc.android.sdk.internal.transaction

import cash.z.ecc.android.sdk.exception.TransactionEncoderException
import cash.z.ecc.android.sdk.internal.SaplingParamFetcher
import cash.z.ecc.android.sdk.internal.TypesafeBackend
import cash.z.ecc.android.sdk.internal.jni.ProposalInsufficientFundsException
import cash.z.ecc.android.sdk.internal.jni.ProposalRejectedException
import cash.z.ecc.android.sdk.internal.repository.DerivedDataRepository
import cash.z.ecc.android.sdk.model.Account
import cash.z.ecc.android.sdk.model.AccountUuid
import cash.z.ecc.android.sdk.model.RecipientAddress
import cash.z.ecc.android.sdk.model.Zatoshi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import kotlin.reflect.KClass
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame

/**
 * The upstream twin of [com.zodl.slipstream.internal.spend.SlipstreamSpendServiceErrorMappingTest]:
 * both engines must report the Rust layer's insufficient-funds failure as the same typed exception,
 * and keep the per-operation wrapper for everything else.
 */
class TransactionEncoderImplErrorMappingTest {
    private val account = Account.new(AccountUuid.new(ByteArray(ACCOUNT_UUID_SIZE)))

    private val insufficientFunds = RuntimeException("Insufficient balance (have 0, need 1510000 including fee)")

    private val unrelated = RuntimeException("the database is locked")

    @Test
    fun proposeTransferMapsInsufficientBalance() =
        runBlocking {
            val backend = mock(TypesafeBackend::class.java)
            `when`(backend.proposeTransfer(account, RECIPIENT, AMOUNT.value, null)).thenThrow(insufficientFunds)

            val exception =
                assertFailsWith<TransactionEncoderException.InsufficientFundsException> {
                    encoder(backend).proposeTransfer(account, RECIPIENT, AMOUNT, null)
                }

            assertSame(insufficientFunds, exception.rootCause)
            assertSame(insufficientFunds, exception.cause)
        }

    @Test
    fun proposeTransferMapsOtherFailuresToTheParametersWrapper() =
        runBlocking {
            val backend = mock(TypesafeBackend::class.java)
            `when`(backend.proposeTransfer(account, RECIPIENT, AMOUNT.value, null)).thenThrow(unrelated)

            val exception =
                assertFailsWith<TransactionEncoderException.ProposalFromParametersException> {
                    encoder(backend).proposeTransfer(account, RECIPIENT, AMOUNT, null)
                }

            assertSame(unrelated, exception.rootCause)
        }

    @Test
    fun proposeTransferFromUriMapsInsufficientBalance() =
        runBlocking {
            val backend = mock(TypesafeBackend::class.java)
            `when`(backend.proposeTransferFromUri(account, URI)).thenThrow(insufficientFunds)

            val exception =
                assertFailsWith<TransactionEncoderException.InsufficientFundsException> {
                    encoder(backend).proposeTransferFromUri(account, URI)
                }

            assertSame(insufficientFunds, exception.rootCause)
        }

    @Test
    fun proposeTransferFromUriMapsOtherFailuresToTheUriWrapper() =
        runBlocking {
            val backend = mock(TypesafeBackend::class.java)
            `when`(backend.proposeTransferFromUri(account, URI)).thenThrow(unrelated)

            val exception =
                assertFailsWith<TransactionEncoderException.ProposalFromUriException> {
                    encoder(backend).proposeTransferFromUri(account, URI)
                }

            assertSame(unrelated, exception.rootCause)
        }

    @Test
    fun proposeShieldingMapsInsufficientBalance() =
        runBlocking {
            val backend = mock(TypesafeBackend::class.java)
            `when`(backend.proposeShielding(account, THRESHOLD.value, null, null)).thenThrow(insufficientFunds)

            val exception =
                assertFailsWith<TransactionEncoderException.InsufficientFundsException> {
                    encoder(backend).proposeShielding(account, THRESHOLD, null, null)
                }

            assertSame(insufficientFunds, exception.rootCause)
        }

    @Test
    fun proposeShieldingMapsOtherFailuresToTheShieldingWrapper() =
        runBlocking {
            val backend = mock(TypesafeBackend::class.java)
            `when`(backend.proposeShielding(account, THRESHOLD.value, null, null)).thenThrow(unrelated)

            val exception =
                assertFailsWith<TransactionEncoderException.ProposalShieldingException> {
                    encoder(backend).proposeShielding(account, THRESHOLD, null, null)
                }

            assertSame(unrelated, exception.rootCause)
        }

    @Test
    fun proposeOrchardToIronwoodMigrationMapsInsufficientBalance() =
        runBlocking {
            val backend = mock(TypesafeBackend::class.java)
            `when`(backend.proposeOrchardToIronwoodMigration(account)).thenThrow(insufficientFunds)

            val exception =
                assertFailsWith<TransactionEncoderException.InsufficientFundsException> {
                    encoder(backend).proposeOrchardToIronwoodMigration(account)
                }

            assertSame(insufficientFunds, exception.rootCause)
        }

    @Test
    fun proposeOrchardToIronwoodMigrationMapsOtherFailuresToTheParametersWrapper() =
        runBlocking {
            val backend = mock(TypesafeBackend::class.java)
            `when`(backend.proposeOrchardToIronwoodMigration(account)).thenThrow(unrelated)

            val exception =
                assertFailsWith<TransactionEncoderException.ProposalFromParametersException> {
                    encoder(backend).proposeOrchardToIronwoodMigration(account)
                }

            assertSame(unrelated, exception.rootCause)
        }

    @Test
    fun proposeSendMaxMapsInsufficientFunds() {
        val refusal = ProposalInsufficientFundsException("Insufficient balance (available 0, required 10000)")

        val exception = assertIs<TransactionEncoderException.InsufficientFundsException>(sendMaxFailure(refusal))

        assertSame(refusal, exception.rootCause)
    }

    @Test
    fun proposeSendMaxMapsEachRejectionToItsTypedException() {
        val expected =
            mapOf(
                ProposalRejectedException.TRANSACTION_TOO_LARGE to
                    TransactionEncoderException.ProposalTooLargeException::class,
                ProposalRejectedException.TRANSPARENT_MEMO to
                    TransactionEncoderException.ProposalRecipientCannotReceiveMemoException::class,
                ProposalRejectedException.UNSUPPORTED_ADDRESS to
                    TransactionEncoderException.ProposalUnsupportedRecipientException::class,
                ProposalRejectedException.SYNC_REQUIRED to
                    TransactionEncoderException.ProposalSyncRequiredException::class
            )

        for ((reason, type) in expected) {
            val refusal = ProposalRejectedException(reason)

            val exception = sendMaxFailure(refusal)

            assertEquals(type, exception::class, reason)
            assertSame(refusal, exception.cause)
        }
    }

    @Test
    fun proposeSendMaxMapsOtherFailuresToTheParametersWrapper() {
        val exception =
            assertIs<TransactionEncoderException.ProposalFromParametersException>(sendMaxFailure(unrelated))

        assertSame(unrelated, exception.rootCause)
    }

    @Test
    fun proposeTransferRethrowsCancellationInsteadOfWrapping(): Unit =
        runBlocking {
            val backend = mock(TypesafeBackend::class.java)
            `when`(
                backend.proposeTransfer(account, RECIPIENT, AMOUNT.value, null)
            ).thenThrow(CancellationException("scope cancelled"))

            assertFailsWith<CancellationException> {
                encoder(backend).proposeTransfer(account, RECIPIENT, AMOUNT, null)
            }
        }

    @Test
    fun proposeTransferFromUriRethrowsCancellationInsteadOfWrapping(): Unit =
        runBlocking {
            val backend = mock(TypesafeBackend::class.java)
            `when`(backend.proposeTransferFromUri(account, URI)).thenThrow(CancellationException("scope cancelled"))

            assertFailsWith<CancellationException> {
                encoder(backend).proposeTransferFromUri(account, URI)
            }
        }

    @Test
    fun proposeShieldingRethrowsCancellationInsteadOfWrapping(): Unit =
        runBlocking {
            val backend = mock(TypesafeBackend::class.java)
            `when`(
                backend.proposeShielding(account, THRESHOLD.value, null, null)
            ).thenThrow(CancellationException("scope cancelled"))

            assertFailsWith<CancellationException> {
                encoder(backend).proposeShielding(account, THRESHOLD, null, null)
            }
        }

    @Test
    fun proposeOrchardToIronwoodMigrationRethrowsCancellationInsteadOfWrapping(): Unit =
        runBlocking {
            val backend = mock(TypesafeBackend::class.java)
            `when`(
                backend.proposeOrchardToIronwoodMigration(account)
            ).thenThrow(CancellationException("scope cancelled"))

            assertFailsWith<CancellationException> {
                encoder(backend).proposeOrchardToIronwoodMigration(account)
            }
        }

    @Test
    fun proposeTransferMapsEachRejectionToItsTypedException() =
        assertEachRejectionMaps(
            TransactionEncoderException.ProposalFromParametersException::class
        ) { backend, refusal ->
            `when`(backend.proposeTransfer(account, RECIPIENT, AMOUNT.value, null)).thenThrow(refusal)
            encoder(backend).proposeTransfer(account, RECIPIENT, AMOUNT, null)
        }

    @Test
    fun proposeTransferFromUriMapsEachRejectionToItsTypedException() =
        assertEachRejectionMaps(
            TransactionEncoderException.ProposalFromUriException::class
        ) { backend, refusal ->
            `when`(backend.proposeTransferFromUri(account, URI)).thenThrow(refusal)
            encoder(backend).proposeTransferFromUri(account, URI)
        }

    @Test
    fun proposeShieldingMapsEachRejectionToItsTypedException() =
        assertEachRejectionMaps(
            TransactionEncoderException.ProposalShieldingException::class
        ) { backend, refusal ->
            `when`(backend.proposeShielding(account, THRESHOLD.value, null, null)).thenThrow(refusal)
            encoder(backend).proposeShielding(account, THRESHOLD, null, null)
        }

    /**
     * Asserts that [propose], whose backend call throws each typed refusal the Rust layer raises,
     * surfaces it as the same typed exception a send-max does, with [fallback] for a rejection that
     * has no typed exception of its own.
     */
    private fun assertEachRejectionMaps(
        fallback: KClass<out TransactionEncoderException>,
        propose: suspend (TypesafeBackend, Exception) -> Unit
    ) {
        val expected =
            mapOf(
                ProposalInsufficientFundsException(INSUFFICIENT_FUNDS_MESSAGE) to
                    TransactionEncoderException.InsufficientFundsException::class,
                ProposalRejectedException(ProposalRejectedException.TRANSACTION_TOO_LARGE) to
                    TransactionEncoderException.ProposalTooLargeException::class,
                ProposalRejectedException(ProposalRejectedException.TRANSPARENT_MEMO) to
                    TransactionEncoderException.ProposalRecipientCannotReceiveMemoException::class,
                ProposalRejectedException(ProposalRejectedException.UNSUPPORTED_ADDRESS) to
                    TransactionEncoderException.ProposalUnsupportedRecipientException::class,
                ProposalRejectedException(ProposalRejectedException.SYNC_REQUIRED) to
                    TransactionEncoderException.ProposalSyncRequiredException::class,
                ProposalRejectedException("a_reason_added_later") to fallback
            )

        for ((refusal, type) in expected) {
            val backend = mock(TypesafeBackend::class.java)

            val exception = runBlocking { assertFails { propose(backend, refusal) } }

            assertEquals(type, exception::class, refusal.message)
            assertSame(refusal, exception.cause)
        }
    }

    /** What [TransactionEncoderImpl.proposeSendMax] throws when the backend's send-max call throws [backendFailure]. */
    private fun sendMaxFailure(backendFailure: Exception): Throwable =
        runBlocking {
            val backend = mock(TypesafeBackend::class.java)
            val recipient = mock(RecipientAddress::class.java)
            `when`(backend.proposeSendMaxTransfer(account, recipient, null)).thenThrow(backendFailure)
            assertFails { encoder(backend).proposeSendMax(account, recipient, null) }
        }

    private fun encoder(backend: TypesafeBackend): TransactionEncoderImpl =
        TransactionEncoderImpl(
            backend = backend,
            saplingParamFetcher = mock(SaplingParamFetcher::class.java),
            repository = mock(DerivedDataRepository::class.java)
        )

    private companion object {
        const val ACCOUNT_UUID_SIZE = 16
        const val INSUFFICIENT_FUNDS_MESSAGE = "Insufficient balance (available 0, required 10000)"
        const val RECIPIENT = "recipient"
        const val URI = "zcash:recipient?amount=1"
        val AMOUNT = Zatoshi(100_000L)
        val THRESHOLD = Zatoshi(100_000L)
    }
}
