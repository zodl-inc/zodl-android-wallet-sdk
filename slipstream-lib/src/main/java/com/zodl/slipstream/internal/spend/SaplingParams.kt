@file:Suppress("MaxLineLength")

package com.zodl.slipstream.internal.spend

import androidx.annotation.VisibleForTesting
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
     * Retries [block] on [IOException] (covers both `SocketTimeoutException` and transient
     * `UnknownHostException` DNS failures, MOB-1744) up to [maxAttempts] attempts total, with
     * exponential backoff between attempts. Once attempts are exhausted, the original exception is
     * rethrown as-is - never wrapped or swallowed - so callers can still distinguish a timeout
     * from a DNS failure from any other I/O error.
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
                if (attempt >= maxAttempts) {
                    throw e
                }
                // Exponential backoff: 1s, 2s, 4s, ...
                delay(initialBackoffMs shl (attempt - 1))
                attempt++
            }
        }
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
            check(connection.responseCode == HttpURLConnection.HTTP_OK) {
                "Unexpected response ${connection.responseCode} downloading $fileName"
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
     * Tor client behind it. Transfers up to `maxSizeBytes + 1` bytes so a too-large response still fails
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
            check(response.status.value == HttpURLConnection.HTTP_OK) {
                "Unexpected response ${response.status.value} downloading $fileName"
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
