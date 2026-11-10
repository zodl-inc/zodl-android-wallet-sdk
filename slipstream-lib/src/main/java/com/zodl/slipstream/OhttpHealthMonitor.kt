package com.zodl.slipstream

import co.electriccoin.lightwallet.client.health.OhttpHealthCenter
import co.electriccoin.lightwallet.client.health.OhttpHealthRoute
import co.electriccoin.lightwallet.client.health.OhttpHealthSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onStart
import java.util.concurrent.atomic.AtomicLong

/**
 * The OHTTP request-health evidence of one route, from both stacks that carry its traffic: the
 * Slipstream engine ([co.electriccoin.lightwallet.client.health.OhttpHealthSource.NATIVE]) and the
 * Kotlin client ([co.electriccoin.lightwallet.client.health.OhttpHealthSource.CLIENT]).
 *
 * Each emission carries the retained observations of both sources (bounded: the latest per scope, outcome
 * and purpose class, per source). Their versions are only comparable within a source, so the snapshot's
 * own version is this monitor's emission counter. The first emission replays history, flagged
 * `historical`.
 */
class OhttpHealthMonitor(
    private val center: OhttpHealthCenter = OhttpHealthCenter.shared,
) {
    fun observe(route: OhttpHealthRoute): Flow<OhttpHealthSnapshot> =
        flow {
            val sequence = AtomicLong()
            val native = NativeOhttpHealth.observe(route).onStart { emit(OhttpHealthSnapshot(0L, emptyList())) }
            emitAll(
                combine(native, center.updates(route)) { fromEngine, fromClient ->
                    OhttpHealthSnapshot(
                        version = sequence.incrementAndGet(),
                        observations = fromEngine.observations + fromClient.observations,
                    )
                }.distinctUntilChangedBy { it.observations },
            )
        }
}
