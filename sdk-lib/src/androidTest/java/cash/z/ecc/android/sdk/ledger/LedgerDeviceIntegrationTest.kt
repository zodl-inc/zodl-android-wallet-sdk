package cash.z.ecc.android.sdk.ledger

import androidx.test.filters.SmallTest
import cash.z.ecc.android.sdk.exception.LedgerException
import cash.z.ecc.android.sdk.internal.ledger.TypesafeLedgerBackendImpl
import cash.z.ecc.android.sdk.model.AccountUuid
import cash.z.ecc.android.sdk.model.Pczt
import cash.z.ecc.android.sdk.model.UnifiedFullViewingKey
import cash.z.ecc.android.sdk.model.ZcashNetwork
import cash.z.ecc.android.sdk.model.Zip32AccountIndex
import cash.z.ecc.android.sdk.tool.DerivationTool
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration

/**
 * Drives the real native Ledger engine through its JNI boundary against a scripted device that
 * answers with the reply shapes the Zcash app produces. What these prove is that every JNI
 * signature, `Jni*` constructor and exception mapping holds up at runtime; the engine's protocol
 * logic is covered by the Rust tests.
 */
class LedgerDeviceIntegrationTest {
    private val network = ZcashNetwork.Testnet
    private val account = Zip32AccountIndex.new(0)

    /**
     * A device holding the keys of [seed]: it answers `GET_FIRMWARE_VERSION`,
     * `GET_WALLET_PUBLIC_KEY`, `GET_VK` (chunked the way the app chunks it) and `GET_SHIELD_ADDR`,
     * and lets a test script a status word for one exchange.
     */
    private class ScriptedDevice(
        private val ufvk: String,
        private val address: String,
        var publicKey: ByteArray = GENERATOR_UNCOMPRESSED
    ) : LedgerApduTransport {
        val timeouts = mutableListOf<Duration?>()
        val statusAt = mutableMapOf<Int, Int>()
        var closed = false
        private var exchanges = 0
        private var vkPending: ByteArray? = null

        override suspend fun exchange(
            apdu: ByteArray,
            timeout: Duration?
        ): ByteArray {
            val index = exchanges++
            timeouts.add(timeout)
            statusAt[index]?.let { return statusWord(it) }
            val ins = apdu[1].toInt() and 0xFF
            val p1 = apdu[2].toInt() and 0xFF
            val data =
                when (ins) {
                    INS_GET_FIRMWARE_VERSION -> byteArrayOf(0x38, 0x30, 3, 9, 3, 2, 3, 22)
                    INS_GET_WALLET_PUBLIC_KEY -> walletPublicKeyReply()
                    INS_GET_VK -> vkChunk(first = p1 != P1_CONTINUE)
                    INS_GET_SHIELD_ADDR -> lengthPrefixed(address.toByteArray())
                    else -> error("unexpected instruction $ins")
                }
            return data + statusWord(SW_OK)
        }

        override suspend fun close() {
            closed = true
        }

        private fun walletPublicKeyReply(): ByteArray {
            val firstAddress = "tmSoftwareDeviceFirstAddress".toByteArray()
            return byteArrayOf(publicKey.size.toByte()) + publicKey +
                byteArrayOf(firstAddress.size.toByte()) + firstAddress + ByteArray(CHAIN_CODE_SIZE) { 0x11 }
        }

        private fun vkChunk(first: Boolean): ByteArray {
            if (first) {
                vkPending = lengthPrefixed(ufvk.toByteArray())
            }
            val pending = checkNotNull(vkPending)
            val chunk = pending.copyOfRange(0, minOf(VK_CHUNK, pending.size))
            vkPending = pending.copyOfRange(chunk.size, pending.size)
            return chunk
        }

        private fun lengthPrefixed(bytes: ByteArray) =
            byteArrayOf((bytes.size shr 8).toByte(), bytes.size.toByte()) + bytes

        private fun statusWord(sw: Int) = byteArrayOf((sw shr 8).toByte(), sw.toByte())
    }

    private suspend fun fixture(): ScriptedDevice {
        val seed = ByteArray(32) { 7 }
        val derivation = DerivationTool.getInstance()
        val ufvk = derivation.deriveUnifiedFullViewingKeys(seed, network, 1).first().encoding
        val address = derivation.deriveUnifiedAddress(seed, network, account)
        return ScriptedDevice(ufvk, address)
    }

    @Test
    @SmallTest
    fun pairing_exports_the_devices_viewing_key_through_the_native_engine() =
        runTest {
            val device = fixture()
            val ledger = LedgerDevice.new(device, network)

            val pairing = ledger.pairAccount(account)

            val expected =
                DerivationTool.getInstance().deriveUnifiedFullViewingKeys(ByteArray(32) { 7 }, network, 1).first()
            assertEquals(expected, pairing.ufvk)
            assertTrue(
                pairing.binding.deviceIdentity.encoding
                    .startsWith("tpk0-")
            )
            assertEquals(pairing.binding.deviceIdentity, ledger.deviceIdentity())
            val identity = pairing.binding.deviceIdentity
            assertEquals(identity, LedgerDeviceIdentity.new(identity.encoding))
            assertEquals(LedgerAppVersion(3, 9, 3, supportsPczt = true), pairing.appVersion)
            assertFalse(pairing.binding.toString().contains("tpk0-"))
            // The export request waits on the user; the continuations do not.
            assertNull(device.timeouts[2])
            assertTrue(
                device.timeouts
                    .drop(3)
                    .dropLast(1)
                    .all { it != null }
            )
        }

    @Test
    @SmallTest
    fun a_device_answering_with_another_key_has_another_identity() =
        runTest {
            val device = fixture()
            val ledger = LedgerDevice.new(device, network)
            val identity = ledger.deviceIdentity()
            device.publicKey = OTHER_POINT_UNCOMPRESSED
            assertNotEquals(identity, ledger.deviceIdentity())
        }

    @Test
    @SmallTest
    fun a_refused_frame_is_resent_and_a_denial_is_a_rejection() =
        runTest {
            val device = fixture()
            device.statusAt[0] = SW_CMD_NOT_ACCEPTED
            device.statusAt[2] = SW_DENY
            val ledger = LedgerDevice.new(device, network)

            assertTrue(ledger.appVersion().supportsPczt)
            val error =
                assertFailsWith<LedgerException.UserRejected> {
                    ledger.displayUnifiedAddress(account)
                }
            assertTrue(error.isRestartable)
            assertFalse(device.closed)
        }

    @Test
    @SmallTest
    fun the_device_shows_the_unified_address() =
        runTest {
            val device = fixture()
            val address = LedgerDevice.new(device, network).displayUnifiedAddress(account, transparentAddressIndex = 3)
            assertTrue(address.startsWith("utest1"))
            assertNull(device.timeouts.single())
        }

    @Test
    @SmallTest
    fun an_invalid_identity_or_index_is_invalid_input() =
        runTest {
            assertFailsWith<LedgerException.InvalidInput> {
                LedgerDeviceIdentity.new("tpk0-not-an-identity")
            }
            assertFailsWith<LedgerException.InvalidInput> {
                LedgerDevice.new(fixture(), network).displayUnifiedAddress(account, transparentAddressIndex = 50_001)
            }
        }

    @Test
    @SmallTest
    fun ble_frames_reassemble_through_a_native_deframer() =
        runTest {
            val backend = TypesafeLedgerBackendImpl.new()
            val reply = ByteArray(300) { it.toByte() } + byteArrayOf(0x90.toByte(), 0x00)
            val frames = backend.bleFrames(reply, frameSize = 23)
            assertTrue(frames.size > 1)
            assertEquals(20, backend.parseBleMtuResponse(byteArrayOf(0x08, 0, 0, 0x14, 1, 20)))
            assertContentEquals(byteArrayOf(0x08, 0, 0, 0, 0), backend.bleMtuRequest())

            backend.newBleDeframer().use { deframer ->
                val results = frames.map { deframer.push(it) }
                assertTrue(results.dropLast(1).all { it == null })
                assertContentEquals(reply, results.last())
                assertFailsWith<LedgerException.MalformedReply> { deframer.push(byteArrayOf(0x05, 0, 7)) }
            }
        }

    @Test
    @SmallTest
    fun a_signing_session_refuses_an_invalid_device_identity_before_reading_the_wallet() =
        runTest {
            val backend = TypesafeLedgerBackendImpl.new()
            assertFailsWith<LedgerException.InvalidInput> {
                backend.newSignSession(
                    dataDbFile = File.createTempFile("ledger", ".sqlite"),
                    network = network,
                    accountUuid = AccountUuid.new(ByteArray(16)),
                    pczt = Pczt(byteArrayOf(1, 2, 3)),
                    deviceIdentity = LedgerDeviceIdentity("tpk0-invalid"),
                    zip32AccountIndex = account,
                    firmwareVersionReply = byteArrayOf(0x38, 0x30, 3, 9, 3, 2, 3, 22, 0x90.toByte(), 0x00)
                )
            }
        }

    @Test
    @SmallTest
    fun a_pairing_result_does_not_print_the_viewing_key() {
        val pairing =
            LedgerAccountPairing(
                ufvk = UnifiedFullViewingKey("uviewtest1secret"),
                binding = LedgerAccountBinding(LedgerDeviceIdentity("tpk0-" + "a".repeat(64)), account),
                appVersion = LedgerAppVersion(3, 9, 3, supportsPczt = true)
            )
        assertFalse(pairing.toString().contains("uviewtest1secret"))
        assertFalse(pairing.toString().contains("a".repeat(64)))
    }

    companion object {
        private const val INS_GET_WALLET_PUBLIC_KEY = 0x40
        private const val INS_GET_FIRMWARE_VERSION = 0xC4
        private const val INS_GET_VK = 0x50
        private const val INS_GET_SHIELD_ADDR = 0x51
        private const val P1_CONTINUE = 0x80
        private const val VK_CHUNK = 255
        private const val CHAIN_CODE_SIZE = 32
        private const val SW_OK = 0x9000
        private const val SW_DENY = 0x6985
        private const val SW_CMD_NOT_ACCEPTED = 0x6901

        /** The secp256k1 generator, uncompressed: a valid public key. */
        private val GENERATOR_UNCOMPRESSED =
            hex(
                "0479BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798" +
                    "483ADA7726A3C4655DA4FBFC0E1108A8FD17B448A68554199C47D08FFB10D4B8"
            )

        /** 2G, uncompressed. */
        private val OTHER_POINT_UNCOMPRESSED =
            hex(
                "04C6047F9441ED7D6D3045406E95C07CD85C778E4B8CEF3CA7ABAC09B95C709EE5" +
                    "1AE168FEA63DC339A3C58419466CEAEEF7F632653266D0E1236431A950CFE52A"
            )

        private fun hex(value: String) =
            ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
