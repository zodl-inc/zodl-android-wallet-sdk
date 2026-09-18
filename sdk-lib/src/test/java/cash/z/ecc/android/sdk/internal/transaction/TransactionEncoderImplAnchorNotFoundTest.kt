package cash.z.ecc.android.sdk.internal.transaction

import cash.z.ecc.android.sdk.exception.PcztException
import cash.z.ecc.android.sdk.exception.TransactionEncoderException
import cash.z.ecc.android.sdk.internal.SaplingParamFetcher
import cash.z.ecc.android.sdk.internal.TypesafeBackend
import cash.z.ecc.android.sdk.internal.jni.ProposalAnchorNotFoundException
import cash.z.ecc.android.sdk.internal.repository.DerivedDataRepository
import cash.z.ecc.android.sdk.model.Account
import cash.z.ecc.android.sdk.model.AccountUuid
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.Pczt
import cash.z.ecc.android.sdk.model.Proposal
import cash.z.ecc.android.sdk.model.UnifiedSpendingKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Pins the mapping MOB-1616 exists for: a native [ProposalAnchorNotFoundException] reaching
 * [TransactionEncoderImpl] through [TypesafeBackend.createProposedTransactions] or
 * [TypesafeBackend.createPcztFromProposal] must surface as the typed
 * [TransactionEncoderException.AnchorNotFoundException], not fall back to the generic wrapper.
 * The upstream revision of this change (zcash/zcash-android-wallet-sdk#2114) instrumented entry
 * points that could never actually throw this exception, so the mapping looked correct but
 * never fired; these tests exercise the mapping directly against a faked [TypesafeBackend],
 * independent of the native layer.
 */
class TransactionEncoderImplAnchorNotFoundTest {
    private val proposal: Proposal = mock(Proposal::class.java)
    private val usk: UnifiedSpendingKey = mock(UnifiedSpendingKey::class.java)
    private val accountUuid = AccountUuid.new(ByteArray(ACCOUNT_UUID_SIZE))
    private val account = Account.new(accountUuid)

    private val anchorNotFound =
        ProposalAnchorNotFoundException("no anchor is computable at height $ANCHOR_HEIGHT", ANCHOR_HEIGHT)

    private val unrelated = RuntimeException("the database is locked")

    @Test
    fun createProposedTransactionsMapsAnchorNotFound() =
        runBlocking {
            val backend = mock(TypesafeBackend::class.java)
            `when`(backend.createProposedTransactions(proposal, usk)).thenThrow(anchorNotFound)

            val exception =
                assertFailsWith<TransactionEncoderException.AnchorNotFoundException> {
                    encoder(backend).createProposedTransactions(proposal, usk)
                }

            assertEquals(BlockHeight.new(ANCHOR_HEIGHT), exception.anchorHeight)
            assertSame(anchorNotFound, exception.cause)
        }

    @Test
    fun createProposedTransactionsMapsOtherFailuresToTheGenericWrapper() =
        runBlocking {
            val backend = mock(TypesafeBackend::class.java)
            `when`(backend.createProposedTransactions(proposal, usk)).thenThrow(unrelated)

            val exception =
                assertFailsWith<TransactionEncoderException.TransactionNotCreatedException> {
                    encoder(backend).createProposedTransactions(proposal, usk)
                }

            assertSame(unrelated, exception.rootCause)
        }

    @Test
    fun createPcztFromProposalMapsAnchorNotFoundAsCause() =
        runBlocking {
            val backend = mock(TypesafeBackend::class.java)
            `when`(backend.createPcztFromProposal(account, proposal)).thenThrow(anchorNotFound)

            val exception =
                assertFailsWith<PcztException.CreatePcztFromProposalException> {
                    encoder(backend).createPcztFromProposal(accountUuid, proposal)
                }

            val cause = exception.cause
            check(cause is TransactionEncoderException.AnchorNotFoundException)
            assertEquals(BlockHeight.new(ANCHOR_HEIGHT), cause.anchorHeight)
            assertSame(anchorNotFound, cause.cause)
        }

    @Test
    fun createPcztFromProposalKeepsThePreviousCauseForOtherFailures() =
        runBlocking {
            val backend = mock(TypesafeBackend::class.java)
            `when`(backend.createPcztFromProposal(account, proposal)).thenThrow(unrelated)

            val exception =
                assertFailsWith<PcztException.CreatePcztFromProposalException> {
                    encoder(backend).createPcztFromProposal(accountUuid, proposal)
                }

            assertNull(exception.cause)
        }

    @Test
    fun addProofsToPcztRethrowsCancellationInsteadOfWrapping(): Unit =
        runBlocking {
            val backend = mock(TypesafeBackend::class.java)
            val pczt = mock(Pczt::class.java)
            `when`(backend.addProofsToPczt(pczt)).thenThrow(CancellationException("scope cancelled"))

            assertFailsWith<CancellationException> {
                encoder(backend).addProofsToPczt(pczt)
            }
        }

    private fun encoder(backend: TypesafeBackend): TransactionEncoderImpl =
        TransactionEncoderImpl(
            backend = backend,
            saplingParamFetcher = mock(SaplingParamFetcher::class.java),
            repository = mock(DerivedDataRepository::class.java)
        )

    private companion object {
        const val ACCOUNT_UUID_SIZE = 16
        const val ANCHOR_HEIGHT = 2_800_000L
    }
}
