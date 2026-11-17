package cash.z.ecc.android.sdk.ext

import cash.z.ecc.android.sdk.model.Zatoshi
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals

class ZatoshiToZecExactTest {
    @Test
    fun keeps_every_zatoshi() {
        val zec = Zatoshi(123_456_789L).toZecExact()

        assertEquals(BigDecimal("1.23456789"), zec)
        assertEquals(8, zec.scale())
        assertEquals(Zatoshi(123_456_789L), zec.convertZecToZatoshi())
    }

    @Test
    fun differs_from_the_rounding_display_conversion() {
        assertEquals(BigDecimal("1.234568"), Zatoshi(123_456_789L).convertZatoshiToZec())
        assertEquals(BigDecimal("1.23456789"), Zatoshi(123_456_789L).toZecExact())
    }

    @Test
    fun converts_zero_and_one_zatoshi() {
        assertEquals(BigDecimal("0.00000000"), Zatoshi(0L).toZecExact())
        assertEquals(BigDecimal("0.00000001"), Zatoshi(1L).toZecExact())
    }

    @Test
    fun converts_the_maximum_supply() {
        val max = Zatoshi(Zatoshi.MAX_INCLUSIVE)

        assertEquals(BigDecimal("21000000.00000000"), max.toZecExact())
        assertEquals(max, max.toZecExact().convertZecToZatoshi())
    }
}
