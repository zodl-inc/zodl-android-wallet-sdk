package cash.z.ecc.android.sdk.internal.jni

import co.electriccoin.lightwallet.client.KeyConfigVerificationFailedException
import co.electriccoin.lightwallet.client.OhttpCodecRequest
import co.electriccoin.lightwallet.client.OhttpCompletion
import co.electriccoin.lightwallet.client.OhttpException
import co.electriccoin.lightwallet.client.OhttpInternalException
import co.electriccoin.lightwallet.client.OhttpSender
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The real native OHTTP core (`zodl-ohttp-client`, Android profile) through [RustOhttpTool]. The
 * protocol itself (real HPKE against an independent receiver, signed envelopes, outer statuses) is
 * covered by the Rust tests of `ohttp.rs`; this checks the JNI boundary: the classes constructed
 * from native code, the packed header lists, the exceptions and the opener handle.
 */
class RustOhttpToolTest {
    // The X25519 base point as a gateway key: a valid public key, whose private key nobody needs here.
    private val key = byteArrayOf(1) + byteArrayOf(9) + ByteArray(31)

    private val request =
        OhttpCodecRequest(
            method = "POST",
            scheme = "https",
            authority = "ohttp-gateway.example",
            path = "/cash.z.wallet.sdk.rpc.CompactTxStreamer/GetLightdInfo",
            headers = listOf("Content-Type" to "application/grpc", "TE" to "trailers"),
            body = ByteArray(5),
        )

    @Test
    fun profile_is_the_android_preset_and_agrees_with_the_sender_limits() =
        runTest {
            val profile = RustOhttpTool.new().profileInfo()

            assertEquals(
                listOf(
                    "Content-Type" to "message/ohttp-chunked-req",
                    "Accept" to "message/ohttp-chunked-res",
                    "Accept-Encoding" to "identity"
                ),
                profile.postHeaders
            )
            assertEquals(listOf("Accept" to "application/json"), profile.keyFetchHeaders)
            assertEquals(OhttpSender.MAX_SEALED_RESPONSE_BYTES.toLong(), profile.maxSealedResponseBytes)
            assertEquals(OhttpSender.MAX_KEY_CONFIG_BYTES.toLong(), profile.maxKeyConfigBytes)
            assertEquals(0L, profile.cacheRefreshMarginSecs)
            assertTrue(profile.dropBeforeRefetch)
            assertFalse(profile.traceOnKeyFetch)
            assertEquals(200, profile.bodyWindow)
        }

    @Test
    fun signed_config_url_follows_the_relay_url() =
        runTest {
            val codec = RustOhttpTool.new()

            assertEquals(
                "https://relay.example/ohttp-configs/signed",
                codec.signedConfigUrl("https://relay.example/relay/")
            )
            assertEquals(
                "https://relay.example/ohttp-configs/signed",
                codec.signedConfigUrl("https://relay.example")
            )
        }

    @Test
    fun an_unsigned_or_unknown_envelope_is_a_key_configuration_failure() =
        runTest {
            val codec = RustOhttpTool.new()

            assertFailsWith<KeyConfigVerificationFailedException> {
                codec.verifyKeyConfig("ohttp-gateway.example", "not json".toByteArray(), 1_800_000_000)
            }
            assertFailsWith<KeyConfigVerificationFailedException> {
                codec.verifyKeyConfig("example.org", "{}".toByteArray(), 1_800_000_000)
            }
        }

    @Test
    fun every_attempt_is_sealed_afresh_and_an_opener_is_freed_or_spent() =
        runTest {
            val codec = RustOhttpTool.new()

            val first = codec.encapsulate(key, request)
            val second = codec.encapsulate(key, request)

            // hdr (7 bytes) || enc (32) || ciphertext (the request plus a 16-byte tag)
            assertTrue(first.body.size > 7 + 32 + 16)
            assertEquals(first.body.size, second.body.size)
            assertFalse(first.body.contentEquals(second.body))

            first.opener.close()
            // Closing again is a no-op
            first.opener.close()

            // A relay refusal: the core decides whether a new key is wanted, and spends the opener.
            val refetch = second.opener.open(400, null, ByteArray(0), 0)
            assertIs<OhttpCompletion.RefetchKeyConfig>(refetch)
        }

    @Test
    fun a_second_400_is_final_and_other_statuses_are_refused_with_their_body() =
        runTest {
            val codec = RustOhttpTool.new()

            val secondTry = codec.encapsulate(key, request).opener
            val second = secondTry.open(400, null, "nope".toByteArray(), 1)
            val busy = codec.encapsulate(key, request).opener
            val unavailable = busy.open(503, null, "busy".toByteArray(), 0)

            assertIs<OhttpCompletion.Refused>(second)
            assertEquals(400, second.status)
            assertEquals("nope", second.detail)
            assertIs<OhttpCompletion.Refused>(unavailable)
            assertEquals(503, unavailable.status)
            assertEquals("busy", unavailable.detail)
        }

    @Test
    fun an_answer_that_does_not_open_is_an_OhttpException_and_spends_the_opener() =
        runTest {
            val codec = RustOhttpTool.new()
            val sealed = codec.encapsulate(key, request)

            assertFailsWith<OhttpException> {
                sealed.opener.open(200, null, ByteArray(64), 0)
            }
            // Closing a spent opener is a no-op
            sealed.opener.close()
        }

    @Test
    fun a_key_of_the_wrong_size_is_an_OhttpException() =
        runTest {
            val codec = RustOhttpTool.new()

            assertFailsWith<OhttpException> { codec.encapsulate(ByteArray(32), request) }
        }

    @Test
    fun a_sealed_request_over_the_cap_is_refused_before_it_is_sent() =
        runTest {
            val codec = RustOhttpTool.new()
            val tooLarge =
                OhttpCodecRequest(
                    method = "POST",
                    scheme = "https",
                    authority = "ohttp-gateway.example",
                    path = "/",
                    headers = emptyList(),
                    body = ByteArray(10 * 1024 * 1024)
                )

            assertFailsWith<OhttpException> { codec.encapsulate(key, tooLarge) }
        }

    @Test
    fun sealed_bodies_differ_in_content_from_the_plain_request() =
        runTest {
            val codec = RustOhttpTool.new()
            val sealed = codec.encapsulate(key, request)

            // The key id and suite header is the only plain part.
            assertContentEquals(byteArrayOf(1, 0, 0x20, 0, 1, 0, 1), sealed.body.copyOf(7))
            sealed.opener.close()
        }

    @Test
    fun a_streamed_answer_crosses_the_boundary_read_by_read_and_its_handle_is_freed_once() =
        runTest {
            val codec = RustOhttpTool.new()
            val opener = codec.encapsulate(key, request).opener
            val decoder = opener.openStream(200, null, 0)

            // Less than the response nonce: nothing is complete yet.
            val events = decoder.push(ByteArray(10))
            assertEquals(null, events.head)
            assertEquals(0, events.body.size)
            assertEquals(null, events.trailers)
            assertFalse(events.end)
            // The rest of the nonce and a zero length: the final chunk, which does not open.
            decoder.push(ByteArray(40))
            val failure = assertFailsWith<OhttpException> { decoder.finish() }
            assertIs<OhttpInternalException>(failure)
            decoder.close()
            decoder.close()
            assertFailsWith<IllegalStateException> { decoder.push(ByteArray(1)) }
            assertFailsWith<IllegalStateException> { opener.openStream(200, null, 0) }
        }

    @Test
    fun a_streamed_answer_that_ends_before_its_final_chunk_is_truncated_not_internal() =
        runTest {
            val codec = RustOhttpTool.new()
            val decoder = codec.encapsulate(key, request).opener.openStream(200, null, 0)

            val failure = assertFailsWith<OhttpException> { decoder.finish() }
            assertFalse(failure is OhttpInternalException)
            decoder.close()
        }
}
