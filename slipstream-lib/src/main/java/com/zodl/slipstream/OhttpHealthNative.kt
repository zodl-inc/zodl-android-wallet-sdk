package com.zodl.slipstream

import android.util.Log
import cash.z.ecc.android.sdk.internal.jni.RustBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * P0 SPIKE (throwaway): handle-less binding of the engine's OHTTP request-health stream
 * (slipstream-internal PR #67 `transport_health`). Independent of the engine lifecycle.
 * [await] blocks, so it must run on its own IO thread, never on `SLIPSTREAM_IO`.
 */
object OhttpHealthNative {
    /** Returns a subscription id (> 0), or 0 when [gatewayHost] has no known relay. */
    external fun subscribe(gatewayHost: String, viaTor: Boolean, generation: Long): Long

    /** Blocks until a snapshot newer than [sinceVersion] exists, or timeout; null otherwise. */
    external fun await(id: Long, sinceVersion: Long, timeoutMs: Long): String?

    external fun close(id: Long)

    /** The engine's evidence clock (CLOCK_MONOTONIC ns). */
    external fun monotonicNowNanos(): Long
}

/** Logs every health snapshot for a route to logcat (tag `OhttpHealthSpike`). */
object OhttpHealthSpike {
    private const val TAG = "OhttpHealthSpike"
    private var job: Job? = null
    private var subscriptionId = 0L
    private var generation = 0L

    @Synchronized
    fun start(scope: CoroutineScope, gatewayHost: String, viaTor: Boolean) {
        stop()
        job =
            scope.launch(Dispatchers.IO) {
                RustBackend.loadLibrary()
                generation += 1
                val id = OhttpHealthNative.subscribe(gatewayHost, viaTor, generation)
                if (id == 0L) {
                    Log.w(TAG, "no relay known for $gatewayHost")
                    return@launch
                }
                subscriptionId = id
                val nanoNow = System.nanoTime()
                val engineNow = OhttpHealthNative.monotonicNowNanos()
                Log.i(TAG, "subscribed id=$id host=$gatewayHost tor=$viaTor gen=$generation")
                Log.i(TAG, "clock check: System.nanoTime=$nanoNow engine=$engineNow delta=${engineNow - nanoNow}")
                var since = -1L
                try {
                    while (isActive) {
                        val text = withContext(Dispatchers.IO) { OhttpHealthNative.await(id, since, 5_000) }
                        if (text != null) {
                            val version =
                                Regex("version=(\\d+)").find(text)?.groupValues?.get(1)?.toLong() ?: since
                            since = version
                            val nowMs = (OhttpHealthNative.monotonicNowNanos()) / 1_000_000
                            Log.i(TAG, "snapshot @engineNowMs=$nowMs\n$text")
                        }
                    }
                } finally {
                    OhttpHealthNative.close(id)
                    Log.i(TAG, "closed id=$id")
                }
            }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
    }
}
