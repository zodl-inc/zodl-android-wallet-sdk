package co.electriccoin.lightwallet.client.internal

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class HpkeTest {
    @Test
    fun i2osp_12ByteLength_smallValue() {
        val result = Hpke.i2osp(1, 12)
        val expected = byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x01)
        assertArrayEquals(expected, result)
    }

    @Test
    fun i2osp_12ByteLength_twoByteValue() {
        val result = Hpke.i2osp(0x0102, 12)
        val expected = byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x01, 0x02)
        assertArrayEquals(expected, result)
    }

    @Test
    fun i2osp_12ByteLength_zero() {
        val result = Hpke.i2osp(0, 12)
        val expected = ByteArray(12)
        assertArrayEquals(expected, result)
    }
}
