package cash.z.ecc.android.sdk.internal.model

import cash.z.ecc.android.sdk.internal.jni.JNI_METADATA_KEY_CHAIN_CODE_SIZE
import cash.z.ecc.android.sdk.internal.jni.JNI_METADATA_KEY_SK_SIZE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JniMetadataKeyTest {
    @Test
    fun attributes_within_constraints() {
        val instance =
            JniMetadataKey(
                sk = ByteArray(JNI_METADATA_KEY_SK_SIZE) { 1 },
                chainCode = ByteArray(JNI_METADATA_KEY_CHAIN_CODE_SIZE) { 2 }
            )

        assertTrue(instance.sk.all { it == 1.toByte() })
    }

    @Test
    fun attributes_not_in_constraints() {
        assertFailsWith(IllegalArgumentException::class) {
            JniMetadataKey(
                sk = ByteArray(JNI_METADATA_KEY_SK_SIZE - 1),
                chainCode = ByteArray(JNI_METADATA_KEY_CHAIN_CODE_SIZE)
            )
        }
    }

    @Test
    fun toString_does_not_leak_key_material() {
        val instance =
            JniMetadataKey(
                sk = ByteArray(JNI_METADATA_KEY_SK_SIZE) { 0x42 },
                chainCode = ByteArray(JNI_METADATA_KEY_CHAIN_CODE_SIZE) { 0x43 }
            )

        assertEquals("JniMetadataKey(sk=***, chainCode=***)", instance.toString())
    }
}
