package cash.z.ecc.android.sdk

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.Closeable
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/**
 * [closingOnCancellation], which [Synchronizer.new] runs its construction in: a synchronizer is registered as active
 * and started as soon as it is constructed, so one created by a caller that is cancelled before it gets the instance
 * must be closed rather than left running with nobody to close it.
 */
class ClosingOnCancellationTest {
    private class FakeResource : Closeable {
        var closeCount = 0

        override fun close() {
            closeCount++
        }
    }

    /**
     * The cancellation lands after the resource was created, while the `coroutineScope` that created it completes:
     * that scope then throws, although its block returned the resource.
     */
    @Test
    fun aResourceCreatedBeforeTheCallerIsCancelledIsClosed() =
        runBlocking {
            val resource = FakeResource()
            lateinit var caller: Deferred<FakeResource>
            caller =
                async {
                    closingOnCancellation { keep ->
                        coroutineScope { keep(resource).also { caller.cancel() } }
                    }
                }

            assertFailsWith<CancellationException> { caller.await() }
            assertEquals(1, resource.closeCount)
        }

    @Test
    fun aResourceHandedBackIsNotClosed() =
        runBlocking {
            val resource = FakeResource()

            assertSame(resource, closingOnCancellation { keep -> keep(resource) })
            assertEquals(0, resource.closeCount)
        }

    @Test
    fun aCancellationBeforeAnythingWasCreatedClosesNothing() =
        runBlocking {
            val resource = FakeResource()

            assertFailsWith<CancellationException> {
                closingOnCancellation<FakeResource> { throw CancellationException("before creation") }
            }
            assertEquals(0, resource.closeCount)
        }

    @Test
    fun anotherFailureAfterCreationIsLeftToTheCreator() =
        runBlocking {
            val resource = FakeResource()

            val failure =
                assertFailsWith<IllegalStateException> {
                    closingOnCancellation { keep ->
                        keep(resource)
                        error("failed after creation")
                    }
                }

            assertEquals("failed after creation", failure.message)
            assertEquals(0, resource.closeCount)
        }
}
