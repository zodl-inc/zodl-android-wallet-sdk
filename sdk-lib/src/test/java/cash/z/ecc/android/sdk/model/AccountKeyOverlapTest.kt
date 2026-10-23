package cash.z.ecc.android.sdk.model

import cash.z.ecc.android.sdk.internal.jni.JNI_ACCOUNT_KEY_OVERLAP_ALREADY_IMPORTED
import cash.z.ecc.android.sdk.internal.jni.JNI_ACCOUNT_KEY_OVERLAP_CONFLICTING
import cash.z.ecc.android.sdk.internal.jni.JNI_ACCOUNT_KEY_OVERLAP_WOULD_UPGRADE
import cash.z.ecc.android.sdk.internal.model.JniAccount
import cash.z.ecc.android.sdk.internal.model.JniAccountKeyOverlap
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AccountKeyOverlapTest {
    @Test
    fun new_maps_every_jni_kind() {
        val expected =
            mapOf(
                JNI_ACCOUNT_KEY_OVERLAP_ALREADY_IMPORTED to AccountKeyOverlap.Kind.ALREADY_IMPORTED,
                JNI_ACCOUNT_KEY_OVERLAP_WOULD_UPGRADE to AccountKeyOverlap.Kind.WOULD_UPGRADE,
                JNI_ACCOUNT_KEY_OVERLAP_CONFLICTING to AccountKeyOverlap.Kind.CONFLICTING
            )
        assertEquals(AccountKeyOverlap.Kind.entries.toSet(), expected.values.toSet())

        expected.forEach { (code, kind) ->
            val overlap = AccountKeyOverlap.new(JniAccountKeyOverlap(jniAccount(), code))

            assertEquals(kind, overlap.kind)
            assertEquals(Account.new(jniAccount()), overlap.account)
        }
    }

    @Test
    fun new_rejects_an_unknown_kind() {
        assertFailsWith<IllegalArgumentException> {
            AccountKeyOverlap.new(JniAccountKeyOverlap(jniAccount(), UNKNOWN_KIND))
        }
    }

    @Test
    fun only_an_upgrade_lets_the_import_succeed() {
        assertTrue(overlapOf(AccountKeyOverlap.Kind.ALREADY_IMPORTED).importWouldFail)
        assertFalse(overlapOf(AccountKeyOverlap.Kind.WOULD_UPGRADE).importWouldFail)
        assertTrue(overlapOf(AccountKeyOverlap.Kind.CONFLICTING).importWouldFail)
    }

    @Test
    fun to_string_never_prints_the_account_keys() {
        val text = overlapOf(AccountKeyOverlap.Kind.CONFLICTING).toString()

        assertFalse(text.contains(UFVK), text)
        assertFalse(text.contains(UIVK), text)
    }

    private fun overlapOf(kind: AccountKeyOverlap.Kind) = AccountKeyOverlap(Account.new(jniAccount()), kind)

    private fun jniAccount() =
        JniAccount(
            accountName = "Imported",
            accountUuid = ByteArray(ACCOUNT_UUID_SIZE) { it.toByte() },
            hdAccountIndex = -1L,
            keySource = null,
            seedFingerprint = null,
            ufvk = UFVK,
            uivk = UIVK
        )

    companion object {
        private const val ACCOUNT_UUID_SIZE = 16
        private const val UNKNOWN_KIND = 3
        private const val UFVK = "uviewtest1notarealkeybutmustneverbeprinted"
        private const val UIVK = "uivktest1notarealkeybutmustneverbeprinted"
    }
}
