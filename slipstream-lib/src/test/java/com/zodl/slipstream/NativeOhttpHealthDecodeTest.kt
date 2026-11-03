@file:Suppress("MaxLineLength")

package com.zodl.slipstream

import co.electriccoin.lightwallet.client.health.OhttpHealthOutcome
import co.electriccoin.lightwallet.client.health.OhttpHealthPurpose
import co.electriccoin.lightwallet.client.health.OhttpHealthRoute
import co.electriccoin.lightwallet.client.health.OhttpHealthScope
import co.electriccoin.lightwallet.client.health.OhttpHealthSource
import co.electriccoin.lightwallet.client.health.OhttpHealthStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeOhttpHealthDecodeTest {
    private val route = OhttpHealthRoute("ohttp-gateway.example", "https://relay.test/relay", viaTor = false)

    /** scope, category, retry, stage, purpose, historical, version, request, started, observed, hasOp, opStart, idHi, idLo */
    @Suppress("LongParameterList")
    private fun observation(
        scope: Long,
        category: Long,
        retry: Long = 0,
        stage: Long = 4,
        purpose: Long = 0,
        historical: Long = 0,
        version: Long,
        request: Long,
        hasOperation: Long = 0,
    ) = longArrayOf(scope, category, retry, stage, purpose, historical, version, request, 100, 200, hasOperation, 50, 1, 2)

    @Test
    fun `an observation is decoded field by field`() {
        val raw =
            longArrayOf(7, 12, 1) + observation(scope = 1, category = 1, stage = 0, version = 12, request = 38)

        val snapshot = decodeNativeHealth(raw, route)

        assertEquals(12L, snapshot.version)
        val o = snapshot.observations.single()
        assertEquals(OhttpHealthSource.NATIVE, o.source)
        assertEquals(route, o.route)
        assertEquals(OhttpHealthScope.LIGHTWALLETD, o.scope)
        assertEquals(OhttpHealthOutcome.TransportUnavailable, o.outcome)
        assertEquals(OhttpHealthStage.CONNECTION, o.stage)
        assertEquals(OhttpHealthPurpose.BACKGROUND, o.purpose)
        assertEquals(38L, o.requestId)
        assertEquals(100L, o.startedAtNanos)
        assertEquals(200L, o.observedAtNanos)
        assertFalse(o.historical)
        assertNull(o.operation)
    }

    @Test
    fun `every scope, stage, purpose and outcome category maps to its Kotlin value`() {
        val scopes = OhttpHealthScope.entries
        scopes.forEachIndexed { i, expected ->
            val raw = longArrayOf(0, 1, 1) + observation(scope = i.toLong(), category = 0, version = 1, request = 1)
            assertEquals(expected, decodeNativeHealth(raw, route).observations.single().scope)
        }
        OhttpHealthStage.entries.forEachIndexed { i, expected ->
            val raw = longArrayOf(0, 1, 1) + observation(scope = 0, category = 0, stage = i.toLong(), version = 1, request = 1)
            assertEquals(expected, decodeNativeHealth(raw, route).observations.single().stage)
        }
        OhttpHealthPurpose.entries.forEachIndexed { i, expected ->
            val raw = longArrayOf(0, 1, 1) + observation(scope = 0, category = 0, purpose = i.toLong(), version = 1, request = 1)
            assertEquals(expected, decodeNativeHealth(raw, route).observations.single().purpose)
        }
        val expectedByCategory =
            mapOf(
                0 to OhttpHealthOutcome.Usable,
                1 to OhttpHealthOutcome.TransportUnavailable,
                2 to OhttpHealthOutcome.BackendUnavailable,
                4 to OhttpHealthOutcome.PolicyRejected,
                5 to OhttpHealthOutcome.ConfigurationFailure,
                6 to OhttpHealthOutcome.ResponseRejected,
                7 to OhttpHealthOutcome.Cancelled,
                8 to OhttpHealthOutcome.Inconclusive,
            )
        expectedByCategory.forEach { (category, expected) ->
            val raw = longArrayOf(0, 1, 1) + observation(scope = 0, category = category.toLong(), version = 1, request = 1)
            assertEquals(expected, decodeNativeHealth(raw, route).observations.single().outcome)
        }
    }

    @Test
    fun `a rate limit carries the engine's retry deadline`() {
        val raw = longArrayOf(0, 1, 1) + observation(scope = 0, category = 3, retry = 9_000, version = 1, request = 1)

        assertEquals(
            OhttpHealthOutcome.RateLimited(9_000),
            decodeNativeHealth(raw, route).observations.single().outcome,
        )
    }

    @Test
    fun `historical replay and an operation are decoded`() {
        val raw =
            longArrayOf(0, 2, 1) +
                observation(scope = 1, category = 0, historical = 1, hasOperation = 1, purpose = 1, version = 2, request = 2)

        val o = decodeNativeHealth(raw, route).observations.single()

        assertTrue(o.historical)
        assertNotNull(o.operation)
        assertEquals(50L, o.operation?.startedAtNanos)
        assertEquals(OhttpHealthPurpose.USER_OPERATION, o.purpose)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a snapshot of the wrong size is rejected`() {
        decodeNativeHealth(longArrayOf(0, 1, 2) + observation(scope = 0, category = 0, version = 1, request = 1), route)
    }
}
