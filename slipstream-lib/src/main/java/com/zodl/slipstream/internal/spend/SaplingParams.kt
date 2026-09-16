@file:Suppress("MaxLineLength")

package com.zodl.slipstream.internal.spend

import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Where the Sapling proving parameters live once fetched, for [Backend][cash.z.ecc.android.sdk.internal.Backend] construction. */
internal data class SaplingParamPaths(
    val spendFile: File,
    val outputFile: File
)

/** Downloads sapling-spend/output.params (SHA-1s below) into the caller's dir; needed by the create/addProofsToPczt paths. */
internal object SaplingParams {
    private const val BASE_URL = "https://download.z.cash/downloads/"
    private const val SPEND_FILE_NAME = "sapling-spend.params"
    private const val OUTPUT_FILE_NAME = "sapling-output.params"
    private const val SPEND_SHA1 = "a15ab54c2888880e53c823a3063820c728444126"
    private const val OUTPUT_SHA1 = "0ebc5a1ef3653948e1c46cf7a16071eac4b7e352"
    private const val SPEND_MAX_SIZE_BYTES = 50L * 1024 * 1024
    private const val OUTPUT_MAX_SIZE_BYTES = 5L * 1024 * 1024
    private const val CONNECT_TIMEOUT_MS = 30_000
    private const val READ_TIMEOUT_MS = 30_000

    // MOB-1744: a single stalled attempt (SocketTimeoutException) or a transient DNS hiccup
    // (UnknownHostException resolving download.z.cash) used to fail the whole 50MB spend-params
    // download outright, on the synchronous path a broadcast blocks on. Retry a few times with
    // backoff before surfacing the error to the caller.
    private const val MAX_DOWNLOAD_ATTEMPTS = 3
    private const val INITIAL_RETRY_BACKOFF_MS = 1_000L

    suspend fun ensureDownloaded(destinationDir: File): SaplingParamPaths =
        withContext(Dispatchers.IO) {
            destinationDir.mkdirs()
            val spendFile = File(destinationDir, SPEND_FILE_NAME)
            val outputFile = File(destinationDir, OUTPUT_FILE_NAME)
            ensureFile(spendFile, SPEND_SHA1, SPEND_MAX_SIZE_BYTES)
            ensureFile(outputFile, OUTPUT_SHA1, OUTPUT_MAX_SIZE_BYTES)
            SaplingParamPaths(spendFile, outputFile)
        }

    private suspend fun ensureFile(
        file: File,
        expectedSha1: String,
        maxSizeBytes: Long
    ) {
        if (file.exists() && file.length() in 1..maxSizeBytes && sha1Of(file).equals(expectedSha1, ignoreCase = true)) {
            return
        }
        retryOnIOException { download(file, maxSizeBytes) }
        check(sha1Of(file).equals(expectedSha1, ignoreCase = true)) {
            "SHA-1 mismatch downloading ${file.name}: expected $expectedSha1"
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

    private fun download(
        file: File,
        maxSizeBytes: Long
    ) {
        val connection = URL(BASE_URL + file.name).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            check(connection.responseCode == HttpURLConnection.HTTP_OK) {
                "Unexpected response ${connection.responseCode} downloading ${file.name}"
            }
            val partialFile = File(file.parentFile, "${file.name}.part")
            connection.inputStream.use { input ->
                partialFile.outputStream().use { output ->
                    val copied = input.copyTo(output)
                    check(copied <= maxSizeBytes) { "${file.name} exceeded its expected maximum size" }
                }
            }
            check(partialFile.renameTo(file)) { "Failed to move downloaded ${file.name} into place" }
        } finally {
            connection.disconnect()
        }
    }

    private fun sha1Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-1")
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
