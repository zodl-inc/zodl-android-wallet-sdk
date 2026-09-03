package cash.z.ecc.android.sdk.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class AccountCreateSetupTest {
    @Test
    fun toString_does_not_leak_seed_hex() {
        val seedBytes = ByteArray(64) { 0x11 }
        val setup =
            AccountCreateSetup(
                accountName = "test-account",
                keySource = "test-source",
                seed = FirstClassByteArray(seedBytes)
            )

        val text = setup.toString()

        assertEquals(
            "AccountCreateSetup(accountName=test-account, keySource=test-source, seed=***)",
            text
        )
        assertFalse(text.contains("11111111"))
    }
}
