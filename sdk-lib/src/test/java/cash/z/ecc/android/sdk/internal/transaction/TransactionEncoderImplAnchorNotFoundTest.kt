package cash.z.ecc.android.sdk.internal.transaction

import cash.z.ecc.android.sdk.exception.PcztException
import cash.z.ecc.android.sdk.exception.TransactionEncoderException
import cash.z.ecc.android.sdk.exception.proposalTooLargeReason
import cash.z.ecc.android.sdk.internal.SaplingParamFetcher
import cash.z.ecc.android.sdk.internal.TypesafeBackend
import cash.z.ecc.android.sdk.internal.jni.ProposalAnchorNotFoundException
import cash.z.ecc.android.sdk.internal.jni.ProposalRejectedException
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
 * Also pins the create-time handling of a transaction too large to fit in a block (MOB-2165): the
 * failure is exactly what it always was, and only [proposalTooLargeReason] finds the
 * [ProposalRejectedException] the backend attaches as a suppressed exception.
 *
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

    private val tooLargeReason = ProposalRejectedException(ProposalRejectedException.TRANSACTION_TOO_LARGE)

    // As the backend throws it: the generic exception, with the typed reason only suppressed.
    private val tooLarge = RuntimeException(TOO_LARGE_TEXT).apply { addSuppressed(tooLargeReason) }

    private val unrelated = RuntimeException("the database is locked")

    @Test
    fun createProposedTransactionsMapsAnchorNotFound() =
        runBlocking {
            val backend = mock(TypesafeBackend::class.java)
            `when`(backend.proposalRequiresSaplingProofs(proposal)).thenReturn(false)
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
            `when`(backend.proposalRequiresSaplingProofs(proposal)).thenReturn(false)
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

    /**
     * Characterizes the immediate Orchard to Ironwood migration: its proposal is not size-gated
     * when proposed (see `migration_engine/behaviour_tests.rs`), so a sweep too large for a block
     * fails when its transaction is built. The failure must be exactly main's: a
     * [TransactionEncoderException.TransactionNotCreatedException] (not a subtype) whose
     * `rootCause` is the backend's generic exception with its message, and no `cause`. The typed
     * reason is reachable only through [proposalTooLargeReason].
     */
    @Test
    fun anImmediateMigrationTooLargeToBuildFailsExactlyAsOnMain() =
        runBlocking {
            val backend = mock(TypesafeBackend::class.java)
            `when`(backend.proposalRequiresSaplingProofs(proposal)).thenReturn(false)
            `when`(backend.createProposedTransactions(proposal, usk)).thenThrow(tooLarge)

            val exception =
                runCatching { encoder(backend).createProposedTransactions(proposal, usk) }.exceptionOrNull()

            assertEquals(
                TransactionEncoderException.TransactionNotCreatedException::class,
                exception?.let { it::class }
            )
            check(exception is TransactionEncoderException.TransactionNotCreatedException)
            assertSame(tooLarge, exception.rootCause)
            assertEquals(
                "The transactions have not been successfully created due to: $TOO_LARGE_TEXT",
                exception.message
            )
            assertNull(exception.cause)
            assertSame(tooLargeReason, exception.proposalTooLargeReason()?.rootCause)
        }

    /** The PCZT path for the same failure: main's class, message and (null) cause. */
    @Test
    fun anImmediateMigrationTooLargeForAPcztFailsExactlyAsOnMain() =
        runBlocking {
            val backend = mock(TypesafeBackend::class.java)
            `when`(backend.createPcztFromProposal(account, proposal)).thenThrow(tooLarge)

            val exception =
                runCatching { encoder(backend).createPcztFromProposal(accountUuid, proposal) }.exceptionOrNull()

            assertEquals(PcztException.CreatePcztFromProposalException::class, exception?.let { it::class })
            check(exception != null)
            assertEquals("Failed to create PCZT from proposal with message: $TOO_LARGE_TEXT", exception.message)
            assertNull(exception.cause)
            assertSame(tooLargeReason, exception.proposalTooLargeReason()?.rootCause)
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
        const val TOO_LARGE_TEXT =
            "Error while creating transactions: The proposed transaction is too large to fit in a block"
    }
}
