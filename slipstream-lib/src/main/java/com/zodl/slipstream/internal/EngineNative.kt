@file:Suppress("LongParameterList")

package com.zodl.slipstream.internal

import com.zodl.slipstream.SlipstreamNative
import com.zodl.slipstream.model.SlipstreamEvent
import com.zodl.slipstream.model.SlipstreamSnapshot
import com.zodl.slipstream.model.SlipstreamWalletSummary

/**
 * The native calls [SlipstreamEngine] makes, as an injectable collaborator: [Jni] is the
 * [SlipstreamNative] binding in production; a test supplies a fake, so the engine's status
 * mapping and poll loop are unit-testable without `mockStatic` (the same seam
 * [SlipstreamAnchorSource] is for the preparation tail). Every handle-taking call keeps the
 * binding's contract: on [SlipstreamDispatchers.SLIPSTREAM_IO] only, failures as
 * `RuntimeException`.
 */
internal interface EngineNative {
    suspend fun ensureLoaded()

    fun open(
        dbPath: String,
        serverHost: String,
        serverPort: Int,
        useTls: Boolean,
        networkId: Int,
        totalMemoryBytes: Long
    ): Long

    fun start(
        handle: Long,
        ufvk: String?,
        birthdayHeight: Long,
        torDir: String?
    ): Boolean

    fun stop(handle: Long): Boolean

    fun snapshot(handle: Long): SlipstreamSnapshot

    fun drainEvents(handle: Long): Array<SlipstreamEvent>

    fun walletSummary(
        handle: Long,
        trustedConfirmations: Int,
        untrustedConfirmations: Int,
        allowZeroConfShielding: Boolean
    ): SlipstreamWalletSummary?

    fun notifyTxChange(handle: Long): Boolean

    fun free(handle: Long)

    /** The JNI binding, one-to-one. */
    object Jni : EngineNative {
        override suspend fun ensureLoaded() = SlipstreamNative.ensureLoaded()

        override fun open(
            dbPath: String,
            serverHost: String,
            serverPort: Int,
            useTls: Boolean,
            networkId: Int,
            totalMemoryBytes: Long
        ): Long = SlipstreamNative.open(dbPath, serverHost, serverPort, useTls, networkId, totalMemoryBytes)

        override fun start(
            handle: Long,
            ufvk: String?,
            birthdayHeight: Long,
            torDir: String?
        ): Boolean = SlipstreamNative.start(handle, ufvk, birthdayHeight, torDir)

        override fun stop(handle: Long): Boolean = SlipstreamNative.stop(handle)

        override fun snapshot(handle: Long): SlipstreamSnapshot = SlipstreamNative.snapshot(handle)

        override fun drainEvents(handle: Long): Array<SlipstreamEvent> = SlipstreamNative.drainEvents(handle)

        override fun walletSummary(
            handle: Long,
            trustedConfirmations: Int,
            untrustedConfirmations: Int,
            allowZeroConfShielding: Boolean
        ): SlipstreamWalletSummary? =
            SlipstreamNative.walletSummary(handle, trustedConfirmations, untrustedConfirmations, allowZeroConfShielding)

        override fun notifyTxChange(handle: Long): Boolean = SlipstreamNative.notifyTxChange(handle)

        override fun free(handle: Long) = SlipstreamNative.free(handle)
    }
}
