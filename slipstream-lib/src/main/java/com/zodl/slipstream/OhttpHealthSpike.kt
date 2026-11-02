package com.zodl.slipstream

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Debug aid: logs every health snapshot of a route to logcat (tag `OhttpHealthSpike`). Started from the
 * app's Debug menu by reflection; carries no production behaviour.
 */
object OhttpHealthSpike {
    private const val TAG = "OhttpHealthSpike"
    private var job: Job? = null

    @Synchronized
    fun start(
        scope: CoroutineScope,
        gatewayHost: String,
        viaTor: Boolean,
    ) {
        stop()
        job =
            scope.launch(Dispatchers.IO) {
                val route = NativeOhttpHealth.route(gatewayHost, viaTor)
                if (route == null) {
                    Log.w(TAG, "no relay known for $gatewayHost")
                    return@launch
                }
                Log.i(TAG, "observing $route")
                OhttpHealthMonitor().observe(route).collect { snapshot ->
                    val lines =
                        snapshot.observations.joinToString("\n") {
                            "  ${it.source} ${it.scope} ${it.outcome} ${it.stage} ${it.purpose} " +
                                "hist=${it.historical} req=${it.requestId} observed=${it.observedAtNanos}"
                        }
                    Log.i(TAG, "snapshot #${snapshot.version} n=${snapshot.observations.size}\n$lines")
                }
            }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
    }
}
