package cash.z.ecc.android.sdk.internal.model

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TorRuntimeLeaseTest {
    @Test
    fun handle_is_readable_until_release() =
        runTest {
            val lease = TorRuntimeLease(rawHandle = 42L) {}

            assertEquals(42L, lease.handle)
            assertFalse(lease.isReleased)

            lease.release()

            assertTrue(lease.isReleased)
            assertFailsWith<IllegalStateException> { lease.handle }
        }

    @Test
    fun release_is_idempotent_so_a_holder_can_never_release_someone_elses_pin() =
        runTest {
            var releases = 0
            val lease = TorRuntimeLease(rawHandle = 1L) { releases++ }

            lease.release()
            lease.release()
            lease.release()

            assertEquals(1, releases)
        }

    @Test
    fun release_completes_even_when_called_from_a_cancelled_coroutine() =
        runTest {
            var released = false
            // A suspension point inside the release callback is exactly where a cancelled caller
            // would otherwise throw before the pin count was decremented.
            val lease =
                TorRuntimeLease(rawHandle = 1L) {
                    delay(1)
                    released = true
                }

            val job =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        delay(Long.MAX_VALUE)
                    } finally {
                        lease.release()
                    }
                }
            job.cancel()
            job.join()

            assertTrue(released)
        }
}
