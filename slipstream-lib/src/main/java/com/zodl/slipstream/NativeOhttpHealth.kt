package com.zodl.slipstream

import cash.z.ecc.android.sdk.internal.jni.RustBackend
import co.electriccoin.lightwallet.client.health.OhttpHealthObservation
import co.electriccoin.lightwallet.client.health.OhttpHealthOperation
import co.electriccoin.lightwallet.client.health.OhttpHealthOutcome
import co.electriccoin.lightwallet.client.health.OhttpHealthPurpose
import co.electriccoin.lightwallet.client.health.OhttpHealthRoute
import co.electriccoin.lightwallet.client.health.OhttpHealthScope
import co.electriccoin.lightwallet.client.health.OhttpHealthSnapshot
import co.electriccoin.lightwallet.client.health.OhttpHealthSource
import co.electriccoin.lightwallet.client.health.OhttpHealthStage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * Handle-less binding of the engine's OHTTP request-health stream (slipstream-internal MOB-2300
 * `transport_health`). It does not depend on the engine's lifecycle, which stops and starts with the
 * app's foreground. [await] blocks: never call it on `SlipstreamDispatchers.SLIPSTREAM_IO`.
 */
internal object OhttpHealthNative {
    /** Returns a subscription id (> 0), or 0 when [gatewayHost] has no known relay. */
    external fun subscribe(
        gatewayHost: String,
        viaTor: Boolean,
        generation: Long,
    ): Long

    /** Blocks until a snapshot newer than [sinceVersion] exists, or timeout; null otherwise. See [decodeNativeHealth]. */
    external fun await(
        id: Long,
        sinceVersion: Long,
        timeoutMs: Long,
    ): LongArray?

    external fun close(id: Long)

    /** The relay the engine uses for [gatewayHost] (the key of its health route), or null if it has none. */
    external fun relayUrlFor(gatewayHost: String): String?

    /** The engine's evidence clock (`CLOCK_MONOTONIC` ns). */
    external fun monotonicNowNanos(): Long
}

/** Longs per observation in the flat encoding of `backend-lib/.../slipstream/health.rs`. */
private const val OBSERVATION_STRIDE = 14
private const val HEADER_SIZE = 3

/**
 * Decodes the engine's flat snapshot: `[generation, version, count]` then [OBSERVATION_STRIDE] longs per
 * observation (scope, outcome category, retry-not-before, stage, purpose, historical, version, request id,
 * started, observed, has-operation, operation start, operation id high, operation id low).
 */
internal fun decodeNativeHealth(
    raw: LongArray,
    route: OhttpHealthRoute,
): OhttpHealthSnapshot {
    require(raw.size >= HEADER_SIZE) { "health snapshot too short: ${raw.size}" }
    val count = raw[2].toInt()
    require(raw.size == HEADER_SIZE + count * OBSERVATION_STRIDE) { "health snapshot size ${raw.size} for $count" }
    val observations =
        (0 until count).map { index ->
            val base = HEADER_SIZE + index * OBSERVATION_STRIDE
            OhttpHealthObservation(
                source = OhttpHealthSource.NATIVE,
                route = route,
                scope = OhttpHealthScope.entries[raw[base].toInt()],
                outcome = outcomeOf(raw[base + 1].toInt(), raw[base + 2]),
                stage = OhttpHealthStage.entries[raw[base + 3].toInt()],
                purpose = OhttpHealthPurpose.entries[raw[base + 4].toInt()],
                historical = raw[base + 5] != 0L,
                version = raw[base + 6],
                requestId = raw[base + 7],
                startedAtNanos = raw[base + 8],
                observedAtNanos = raw[base + 9],
                operation =
                    if (raw[base + 10] != 0L) {
                        OhttpHealthOperation(UUID(raw[base + 12], raw[base + 13]).toString(), raw[base + 11])
                    } else {
                        null
                    },
            )
        }
    return OhttpHealthSnapshot(version = raw[1], observations = observations)
}

private fun outcomeOf(
    category: Int,
    retryNotBeforeNanos: Long,
): OhttpHealthOutcome =
    when (category) {
        0 -> OhttpHealthOutcome.Usable
        1 -> OhttpHealthOutcome.TransportUnavailable
        2 -> OhttpHealthOutcome.BackendUnavailable
        3 -> OhttpHealthOutcome.RateLimited(retryNotBeforeNanos)
        4 -> OhttpHealthOutcome.PolicyRejected
        5 -> OhttpHealthOutcome.ConfigurationFailure
        6 -> OhttpHealthOutcome.ResponseRejected
        7 -> OhttpHealthOutcome.Cancelled
        else -> OhttpHealthOutcome.Inconclusive
    }

/** The engine's evidence for one route as a cold [Flow]; the subscription lives while it is collected. */
internal object NativeOhttpHealth {
    private const val AWAIT_SLICE_MS = 2_000L
    private val generations = AtomicLong()

    /** The health route of [gatewayHost], with the engine's own relay URL; null for a gateway without a relay. */
    suspend fun route(
        gatewayHost: String,
        viaTor: Boolean,
    ): OhttpHealthRoute? {
        RustBackend.loadLibrary()
        val relay = withContext(Dispatchers.IO) { OhttpHealthNative.relayUrlFor(gatewayHost) } ?: return null
        return OhttpHealthRoute(gatewayHost.lowercase(), relay, viaTor)
    }

    /**
     * Snapshots of [route], coalesced, each with the engine's retained history first flagged
     * `historical`. Empty (completes at once) when the route's gateway has no known relay or the native
     * library has no health support. The first emission replays history.
     */
    fun observe(route: OhttpHealthRoute): Flow<OhttpHealthSnapshot> =
        flow {
            RustBackend.loadLibrary()
            val id =
                withContext(Dispatchers.IO) {
                    OhttpHealthNative.subscribe(route.gatewayHost, route.viaTor, generations.incrementAndGet())
                }
            if (id == 0L) return@flow
            try {
                var since = -1L
                while (currentCoroutineContext().isActive) {
                    val raw = withContext(Dispatchers.IO) { OhttpHealthNative.await(id, since, AWAIT_SLICE_MS) } ?: continue
                    val snapshot = decodeNativeHealth(raw, route)
                    since = snapshot.version
                    emit(snapshot)
                }
            } finally {
                withContext(NonCancellable + Dispatchers.IO) { OhttpHealthNative.close(id) }
            }
        }.distinctUntilChangedBy { it.observations }
}
