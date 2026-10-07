@file:Suppress("MaxLineLength")

package com.zodl.slipstream.internal.spend

import androidx.annotation.VisibleForTesting
import cash.z.ecc.android.sdk.internal.Backend
import cash.z.ecc.android.sdk.internal.model.TorHttpTransportException
import cash.z.ecc.android.sdk.model.Proposal
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.channels.Channels
import java.security.MessageDigest

/** Where the Sapling proving parameters live once fetched, for [Backend][cash.z.ecc.android.sdk.internal.Backend] construction. */
internal data class SaplingParamPaths(
    val spendFile: File,
    val outputFile: File
)

/**
 * Runs [ensureSaplingParams] only when [proposal] has a Sapling spend or output, as the backend's
 * own `proposal_requires_sapling_proofs` decides: transactions without a Sapling component build
 * without the parameters (the backend then proves with no Sapling prover at all), so a proposal
 * that spends and pays only Orchard or Ironwood never triggers the ~50 MB download. The same rule
 * the upstream `TransactionEncoderImpl.createProposedTransactions` applies.
 */
internal suspend fun ensureSaplingParamsFor(
    backend: Backend,
    proposal: Proposal,
    ensureSaplingParams: suspend () -> Unit
) {
    if (backend.proposalRequiresSaplingProofs(proposal.toUnsafe())) {
        ensureSaplingParams()
    }
}

/** Downloads sapling-spend/output.params (SHA-256s below) into the caller's dir; needed by the create/addProofsToPczt paths. */
internal object SaplingParams {
    private const val BASE_URL = "https://raw.githubusercontent.com/zodl-inc/zcash-sapling-params/main/"

    // Matches CLOUD_PARAM_DIR_URL in sdk-lib/.../SaplingParamTool.kt (files are identical; SHA-256 hashes from SaplingParamTool constants)
    private const val SPEND_FILE_NAME = "sapling-spend.params"
    private const val OUTPUT_FILE_NAME = "sapling-output.params"
    private const val SPEND_SHA256 = "8e48ffd23abb3a5fd9c5589204f32d9c31285a04b78096ba40a79b75677efc13"
    private const val OUTPUT_SHA256 = "2f0ebbcbb9bb0bcffe95a397e7eba89c29eb4dde6191c339db88570e3f3fb0e4"
    private const val SPEND_MAX_SIZE_BYTES = 50L * 1024 * 1024
    private const val OUTPUT_MAX_SIZE_BYTES = 5L * 1024 * 1024
    private const val CONNECT_TIMEOUT_MS = 30_000
    private const val READ_TIMEOUT_MS = 30_000

    // MOB-1744: a single stalled attempt (SocketTimeoutException) or a transient DNS hiccup
    // (UnknownHostException resolving GitHub) used to fail the whole 50MB spend-params
    // download outright, on the synchronous path a broadcast blocks on. Retry a few times with
    // backoff before surfacing the error to the caller.
    private const val MAX_DOWNLOAD_ATTEMPTS = 3
    private const val INITIAL_RETRY_BACKOFF_MS = 1_000L

    /**
     * @param torHttpClientProvider when non-null (Tor enabled), the download is routed through
     * Tor instead of direct HTTPS -- matching [cash.z.ecc.android.sdk.internal.SaplingParamTool]'s
     * behavior. This runs right before proving/tx creation, so with Tor on and this left
     * unset it would silently leak the user's real IP to GitHub at exactly the moment a spend is
     * being prepared -- a strong, precisely-timed deanonymization signal.
     */
    suspend fun ensureDownloaded(
        destinationDir: File,
        torHttpClientProvider: (suspend () -> HttpClient)? = null
    ): SaplingParamPaths =
        withContext(Dispatchers.IO) {
            destinationDir.mkdirs()
            val spendFile = File(destinationDir, SPEND_FILE_NAME)
            val outputFile = File(destinationDir, OUTPUT_FILE_NAME)
            ensureFile(spendFile, SPEND_SHA256, SPEND_MAX_SIZE_BYTES, torHttpClientProvider)
            ensureFile(outputFile, OUTPUT_SHA256, OUTPUT_MAX_SIZE_BYTES, torHttpClientProvider)
            SaplingParamPaths(spendFile, outputFile)
        }

    private suspend fun ensureFile(
        file: File,
        expectedSha256: String,
        maxSizeBytes: Long,
        torHttpClientProvider: (suspend () -> HttpClient)?
    ) {
        if (file.exists() && file.length() in 1..maxSizeBytes && sha256Of(file).equals(expectedSha256, ignoreCase = true)) {
            return
        }
        retryOnIOException { download(file, maxSizeBytes, torHttpClientProvider) }
        check(sha256Of(file).equals(expectedSha256, ignoreCase = true)) {
            "SHA-256 mismatch downloading ${file.name}: expected $expectedSha256"
        }
    }

    /**
     * Retries [block] on a transient network failure up to [maxAttempts] attempts total (so at most
     * `maxAttempts - 1`, by default 2, retries), with exponential backoff between attempts:
     * - [IOException] (direct path: covers both `SocketTimeoutException` and transient
     *   `UnknownHostException` DNS failures, MOB-1744);
     * - [TorHttpTransportException] (Tor path: a Tor circuit/stream or connection failure. These
     *   arrive from the JNI layer as this typed exception, not as [IOException], so without this
     *   case a transient circuit failure would never be retried over Tor).
     *
     * Deliberately NOT retried: every other exception, including the Tor layer's
     * `TorHttpTimeoutException` (the caller's whole time budget is spent, so a retry would at least
     * double the latency it asked for) and non-transport native failures.
     *
     * Once attempts are exhausted, the original exception is rethrown as-is - never wrapped or
     * swallowed - so callers can still distinguish a timeout from a DNS failure from any other
     * error.
     *
     * Extracted as its own function (rather than inlined into [ensureFile]) so retry/backoff
     * behavior can be unit tested directly against a fake failing/succeeding [block], without a
     * real network call.
     */
    @VisibleForTesting
    internal suspend fun retryOnIOException(
        maxAttempts: Int = MAX_DOWNLOAD_ATTEMPTS,
        initialBackoffMs: Long = INITIAL_RETRY_BACKOFF_MS,
        block: suspend () -> Unit
    ) {
        var attempt = 1
        while (true) {
            try {
                block()
                return
            } catch (e: IOException) {
                backOffOrRethrow(e, attempt, maxAttempts, initialBackoffMs)
            } catch (e: TorHttpTransportException) {
                backOffOrRethrow(e, attempt, maxAttempts, initialBackoffMs)
            }
            attempt++
        }
    }

    private suspend fun backOffOrRethrow(
        failure: Exception,
        attempt: Int,
        maxAttempts: Int,
        initialBackoffMs: Long
    ) {
        if (attempt >= maxAttempts) {
            throw failure
        }
        // Exponential backoff: 1s, 2s, 4s, ...
        delay(initialBackoffMs shl (attempt - 1))
    }

    private suspend fun download(
        file: File,
        maxSizeBytes: Long,
        torHttpClientProvider: (suspend () -> HttpClient)?
    ) {
        val partialFile = File(file.parentFile, "${file.name}.part")
        val copied =
            if (torHttpClientProvider != null) {
                downloadViaTor(torHttpClientProvider(), file.name, partialFile, maxSizeBytes)
            } else {
                downloadDirect(file.name, partialFile)
            }
        check(copied <= maxSizeBytes) { "${file.name} exceeded its expected maximum size" }
        check(partialFile.renameTo(file)) { "Failed to move downloaded ${file.name} into place" }
    }

    private fun downloadDirect(
        fileName: String,
        partialFile: File
    ): Long {
        val connection = URL(BASE_URL + fileName).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("Unexpected response ${connection.responseCode} downloading $fileName")
            }
            return connection.inputStream.use { input ->
                partialFile.outputStream().use { output -> input.copyTo(output) }
            }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Downloads [fileName] over Tor and closes [client] afterwards, which releases the isolated native
     * Tor client behind it.
     *
     * The download is bounded by zcash_client_backend's default HTTP-over-Tor timeout (60 seconds
     * whole-body), so the 50 MB spend params need ~0.85 MB/s. zcash_client_backend 0.24 does not
     * expose a create_with_timeouts API to override this, so slower Tor circuits may time out.
     *
     * Transfers up to `maxSizeBytes + 1` bytes so a too-large response still fails
     * the caller's size check instead of being silently truncated at exactly [maxSizeBytes].
     */
    private suspend fun downloadViaTor(
        client: HttpClient,
        fileName: String,
        partialFile: File,
        maxSizeBytes: Long
    ): Long =
        client.use {
            val response = it.get(BASE_URL + fileName)
            if (response.status.value != HttpURLConnection.HTTP_OK) {
                throw IOException("Unexpected response ${response.status.value} downloading $fileName")
            }
            val readableByteChannel = Channels.newChannel(response.bodyAsChannel().toInputStream())
            readableByteChannel.use { source ->
                partialFile.outputStream().channel.use { destination ->
                    destination.transferFrom(source, 0, maxSizeBytes + 1)
                }
            }
        }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var read = input.read(buffer)
            while (read >= 0) {
                digest.update(buffer, 0, read)
                read = input.read(buffer)
            }
        }
        return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}
