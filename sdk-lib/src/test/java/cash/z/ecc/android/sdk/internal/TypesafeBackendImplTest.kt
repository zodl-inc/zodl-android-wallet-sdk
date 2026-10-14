package cash.z.ecc.android.sdk.internal

import cash.z.ecc.android.sdk.fixture.AccountFixture
import cash.z.ecc.android.sdk.internal.jni.JNI_ACCOUNT_KEY_OVERLAP_WOULD_UPGRADE
import cash.z.ecc.android.sdk.internal.model.JniAccount
import cash.z.ecc.android.sdk.internal.model.JniAccountKeyOverlap
import cash.z.ecc.android.sdk.internal.model.JniUnifiedSpendingKey
import cash.z.ecc.android.sdk.model.Account
import cash.z.ecc.android.sdk.model.AccountKeyOverlap
import cash.z.ecc.android.sdk.model.Proposal
import cash.z.ecc.android.sdk.model.UnifiedFullViewingKey
import cash.z.ecc.android.sdk.model.UnifiedSpendingKey
import kotlinx.coroutines.runBlocking
import org.mockito.ArgumentMatchers
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TypesafeBackendImplTest {
    @Test
    fun createProposedTransactions_wipes_the_transient_usk_copy() =
        runBlocking {
            val keyBytes = ByteArray(32) { (it + 1).toByte() }
            val usk = UnifiedSpendingKey(JniUnifiedSpendingKey(bytes = keyBytes.copyOf()))
            val proposal = Proposal.fromByteArray(fakeProposalBytes())

            var capturedUskBytes: ByteArray? = null
            val backend = mock(Backend::class.java)
            `when`(backend.createProposedTransactions(eq(proposal.toUnsafe()), any(), eq(false)))
                .thenAnswer { invocation ->
                    val passedBytes = invocation.getArgument<ByteArray>(1)
                    capturedUskBytes = passedBytes
                    assertContentEquals(keyBytes, passedBytes, "the derived key must reach the backend intact")
                    listOf(byteArrayOf(9))
                }

            TypesafeBackendImpl(backend).createProposedTransactions(proposal, usk)

            val wipedBytes = assertNotNull(capturedUskBytes)
            assertContentEquals(ByteArray(32), wipedBytes, "the transient usk copy must be wiped after use")
        }

    @Test
    fun findAccountSharingKeyComponents_passes_the_encoding_and_maps_the_overlap() =
        runBlocking {
            val ufvk = UnifiedFullViewingKey(OVERLAP_UFVK)
            val jniAccount =
                JniAccount(
                    accountName = "Existing",
                    accountUuid = ByteArray(OVERLAP_ACCOUNT_UUID_SIZE) { it.toByte() },
                    hdAccountIndex = -1L,
                    keySource = null,
                    seedFingerprint = null,
                    ufvk = OVERLAP_UFVK,
                    uivk = null
                )
            val backend = mock(Backend::class.java)
            `when`(backend.findAccountKeyOverlap(OVERLAP_UFVK))
                .thenReturn(JniAccountKeyOverlap(jniAccount, JNI_ACCOUNT_KEY_OVERLAP_WOULD_UPGRADE))

            val overlap = assertNotNull(TypesafeBackendImpl(backend).findAccountSharingKeyComponents(ufvk))

            assertEquals(AccountKeyOverlap.Kind.WOULD_UPGRADE, overlap.kind)
            assertEquals(Account.new(jniAccount), overlap.account)
        }

    @Test
    fun findAccountSharingKeyComponents_returns_null_when_no_account_overlaps() =
        runBlocking {
            val backend = mock(Backend::class.java)
            `when`(backend.findAccountKeyOverlap(OVERLAP_UFVK)).thenReturn(null)

            val ufvk = UnifiedFullViewingKey(OVERLAP_UFVK)
            val overlap = TypesafeBackendImpl(backend).findAccountSharingKeyComponents(ufvk)

            assertNull(overlap)
        }

    @Test
    fun setAccountName_forwards_the_uuid_bytes_and_name() =
        runBlocking<Unit> {
            val accountUuid = AccountFixture.new().accountUuid
            val backend = mock(Backend::class.java)

            TypesafeBackendImpl(backend).setAccountName(accountUuid, RENAMED_ACCOUNT_NAME)
            verify(backend).setAccountName(accountUuid.value, RENAMED_ACCOUNT_NAME)
        }

    /**
     * A minimal, hand-encoded `cash.z.wallet.sdk.ffi.Proposal` protobuf message — see
     * `OrchardMigrationSdkImplTest.fakeProposalBytes` for the full rationale. Just field 2
     * (`feeRule`) set to `Zip317` (3), with no steps, which is enough to round-trip through
     * `Proposal.fromByteArray`.
     */
    private fun fakeProposalBytes(): ByteArray {
        val fieldNumber = 2
        val wireTypeVarint = 0
        val tag = (fieldNumber shl 3) or wireTypeVarint
        val feeRuleZip317 = 3
        return byteArrayOf(tag.toByte(), feeRuleZip317.toByte())
    }

    /**
     * `ArgumentMatchers.eq`/`.any` return `null` (they're platform-typed Java statics that only
     * register a matcher as a side effect). Kotlin inserts a not-null assertion when that `null`
     * flows into a non-null-typed Kotlin parameter, so these wrappers register the real matcher
     * and hand back a value Kotlin already knows is non-null.
     */
    private fun <T> eq(value: T): T = ArgumentMatchers.eq(value) ?: value

    private fun <T> any(): T {
        @Suppress("UNCHECKED_CAST")
        return ArgumentMatchers.any<T>() ?: (null as T)
    }

    companion object {
        private const val OVERLAP_UFVK = "uviewtest1overlapfixture"
        private const val OVERLAP_ACCOUNT_UUID_SIZE = 16
        private const val RENAMED_ACCOUNT_NAME = "Renamed account"
    }
}
