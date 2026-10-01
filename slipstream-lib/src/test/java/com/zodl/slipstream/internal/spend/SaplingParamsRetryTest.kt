package com.zodl.slipstream.internal.spend

import cash.z.ecc.android.sdk.internal.model.TorHttpTimeoutException
import cash.z.ecc.android.sdk.internal.model.TorHttpTransportException
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/**
 * MOB-1744: pins [SaplingParams.retryOnIOException]'s two must-have behaviors from the ticket - a
 * transient `IOException` is retried and a subsequent success stops the loop, and once attempts
 * are exhausted the ORIGINAL exception instance propagates unchanged (not wrapped, not swallowed)
 * so callers can still distinguish a timeout from a DNS failure from any other I/O error. Uses
 * `runTest`'s virtual time so the exponential backoff delays do not slow the suite down.
 */
class SaplingParamsRetryTest {
    @Test
    fun `retries a transient failure and succeeds on the next attempt`() =
        runTest {
            var callCount = 0

            SaplingParams.retryOnIOException(maxAttempts = 3, initialBackoffMs = 1_000L) {
                callCount++
                if (callCount == 1) {
                    throw SocketTimeoutException("read timed out")
                }
            }

            assertEquals(2, callCount)
        }

    @Test
    fun `exhausting all retries rethrows the original exception unchanged`() =
        runTest {
            var callCount = 0
            val originalException = UnknownHostException("raw.githubusercontent.com")

            val thrown =
                assertFailsWith<UnknownHostException> {
                    SaplingParams.retryOnIOException(maxAttempts = 3, initialBackoffMs = 1_000L) {
                        callCount++
                        throw originalException
                    }
                }

            assertEquals(3, callCount)
            assertSame(originalException, thrown)
        }

    @Test
    fun `a non-IOException failure is not retried`() =
        runTest {
            var callCount = 0

            assertFailsWith<IllegalStateException> {
                SaplingParams.retryOnIOException(maxAttempts = 3, initialBackoffMs = 1_000L) {
                    callCount++
                    check(false) { "not an IOException" }
                }
            }

            assertEquals(1, callCount)
        }

    @Test
    fun `retries a Tor transport failure and succeeds on the next attempt`() =
        runTest {
            var callCount = 0

            SaplingParams.retryOnIOException(maxAttempts = 3, initialBackoffMs = 1_000L) {
                callCount++
                if (callCount == 1) {
                    throw TorHttpTransportException("circuit failed")
                }
            }

            assertEquals(2, callCount)
        }

    @Test
    fun `a persistent Tor transport failure is retried at most twice with the default attempts`() =
        runTest {
            var callCount = 0
            val originalException = TorHttpTransportException("circuit failed")

            val thrown =
                assertFailsWith<TorHttpTransportException> {
                    SaplingParams.retryOnIOException {
                        callCount++
                        throw originalException
                    }
                }

            // 1 initial attempt + 2 retries.
            assertEquals(3, callCount)
            assertSame(originalException, thrown)
        }

    @Test
    fun `a Tor timeout is not retried`() =
        runTest {
            var callCount = 0

            assertFailsWith<TorHttpTimeoutException> {
                SaplingParams.retryOnIOException(maxAttempts = 3, initialBackoffMs = 1_000L) {
                    callCount++
                    throw TorHttpTimeoutException("timed out")
                }
            }

            assertEquals(1, callCount)
        }

    @Test
    fun `a generic RuntimeException is not retried`() =
        runTest {
            var callCount = 0

            assertFailsWith<RuntimeException> {
                SaplingParams.retryOnIOException(maxAttempts = 3, initialBackoffMs = 1_000L) {
                    callCount++
                    @Suppress("TooGenericExceptionThrown")
                    throw RuntimeException("native failure")
                }
            }

            assertEquals(1, callCount)
        }

    @Test
    fun `BASE_URL points to GitHub zcash-sapling-params repo`() {
        // Ensures params are fetched from our controlled GitHub repo, matching SaplingParamTool.CLOUD_PARAM_DIR_URL
        val baseUrl =
            SaplingParams::class.java
                .getDeclaredField("BASE_URL")
                .apply { isAccessible = true }
                .get(null) as String
        assertEquals("https://raw.githubusercontent.com/zodl-inc/zcash-sapling-params/main/", baseUrl)
    }
}
