package com.zodl.slipstream.internal.spend

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
            val originalException = UnknownHostException("download.z.cash")

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
}
