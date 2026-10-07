package cash.z.ecc.android.sdk.internal.transaction

import cash.z.ecc.android.sdk.internal.SaplingParamFetcher
import cash.z.ecc.android.sdk.internal.TypesafeBackend
import cash.z.ecc.android.sdk.internal.repository.DerivedDataRepository
import cash.z.ecc.android.sdk.model.OvkPolicy
import cash.z.ecc.android.sdk.model.Proposal
import cash.z.ecc.android.sdk.model.UnifiedSpendingKey
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockingDetails
import org.mockito.Mockito.`when`
import kotlin.test.assertEquals

/**
 * Pins how [TransactionEncoderImpl.createProposedTransactions] drives the backend: the Sapling
 * parameters are fetched only for a proposal that needs them, and the requested OVK policy
 * reaches the backend unchanged.
 */
class TransactionEncoderImplCreateTest {
    private val proposal: Proposal = mock(Proposal::class.java)
    private val usk: UnifiedSpendingKey = mock(UnifiedSpendingKey::class.java)

    private fun SaplingParamFetcher.forceDownloadCount() =
        mockingDetails(this).invocations.count { it.method.name == "forceDownload" }

    private fun TypesafeBackend.createCalls() =
        mockingDetails(this).invocations.filter { it.method.name == "createProposedTransactions" }

    @Test
    fun skipsTheSaplingParametersForAProposalWithoutSapling() =
        runBlocking {
            val backend = mock(TypesafeBackend::class.java)
            `when`(backend.proposalRequiresSaplingProofs(proposal)).thenReturn(false)
            `when`(backend.createProposedTransactions(proposal, usk)).thenReturn(emptyList())
            val fetcher = mock(SaplingParamFetcher::class.java)

            encoder(backend, fetcher).createProposedTransactions(proposal, usk)

            assertEquals(0, fetcher.forceDownloadCount())
            assertEquals(1, backend.createCalls().size)
        }

    @Test
    fun fetchesTheSaplingParametersForASaplingProposal() =
        runBlocking {
            val backend = mock(TypesafeBackend::class.java)
            `when`(backend.proposalRequiresSaplingProofs(proposal)).thenReturn(true)
            `when`(backend.createProposedTransactions(proposal, usk)).thenReturn(emptyList())
            val fetcher = mock(SaplingParamFetcher::class.java)

            encoder(backend, fetcher).createProposedTransactions(proposal, usk)

            assertEquals(1, fetcher.forceDownloadCount())
        }

    @Test
    fun forwardsTheOvkPolicy() =
        runBlocking {
            OvkPolicy.entries.forEach { policy ->
                val backend = mock(TypesafeBackend::class.java)
                `when`(backend.proposalRequiresSaplingProofs(proposal)).thenReturn(false)
                `when`(backend.createProposedTransactions(proposal, usk, policy)).thenReturn(emptyList())

                encoder(backend, mock(SaplingParamFetcher::class.java))
                    .createProposedTransactions(proposal, usk, policy)

                val call = backend.createCalls().single()
                assertEquals(policy, call.arguments[2])
            }
        }

    private fun encoder(
        backend: TypesafeBackend,
        fetcher: SaplingParamFetcher
    ): TransactionEncoderImpl =
        TransactionEncoderImpl(
            backend = backend,
            saplingParamFetcher = fetcher,
            repository = mock(DerivedDataRepository::class.java)
        )
}
