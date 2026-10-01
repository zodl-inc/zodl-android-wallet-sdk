package cash.z.ecc.android.sdk.internal.jni

import cash.z.ecc.android.sdk.internal.model.JniOhttpException
import co.electriccoin.lightwallet.client.KeyConfigVerificationFailedException
import co.electriccoin.lightwallet.client.OhttpException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

/**
 * How a failure of the native OHTTP core reaches the SDK's exception types. Does not load the
 * native library.
 */
class RustOhttpToolFailureTest {
    @Test
    fun `a rejected key configuration is a KeyConfigVerificationFailedException`() {
        val native = JniOhttpException(kind = 1, message = "key configuration: bad signature")

        val mapped = RustOhttpTool.mapFailure(native)

        assertIs<KeyConfigVerificationFailedException>(mapped)
        assertEquals("key configuration: bad signature", mapped.message)
        assertSame(native, mapped.cause)
    }

    @Test
    fun `every other kind is an OhttpException without a relay status`() {
        // seal, open, not a response, binary HTTP, too large, refused, media type, argument, other
        listOf(2, 3, 4, 5, 6, 7, 8, 9, 0).forEach { kind ->
            val native = JniOhttpException(kind = kind, message = "the gateway's answer does not open")

            val mapped = RustOhttpTool.mapFailure(native)

            assertIs<OhttpException>(mapped)
            assertEquals("the gateway's answer does not open", mapped.message)
            assertEquals(null, mapped.httpStatus)
            assertSame(native, mapped.cause)
        }
    }
}
