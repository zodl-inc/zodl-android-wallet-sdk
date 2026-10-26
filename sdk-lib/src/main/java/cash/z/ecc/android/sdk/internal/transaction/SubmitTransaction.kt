package cash.z.ecc.android.sdk.internal.transaction

import cash.z.ecc.android.sdk.internal.SubmissionRefusal
import cash.z.ecc.android.sdk.internal.Twig
import cash.z.ecc.android.sdk.internal.TypesafeBackend
import cash.z.ecc.android.sdk.internal.checkSubmittable
import cash.z.ecc.android.sdk.internal.ext.toHexReversed
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.FirstClassByteArray
import cash.z.ecc.android.sdk.model.SdkFlags
import cash.z.ecc.android.sdk.model.TransactionSubmitResult
import cash.z.ecc.android.sdk.model.ZcashNetwork
import co.electriccoin.lightwallet.client.CombinedWalletClient
import co.electriccoin.lightwallet.client.ServiceMode
import co.electriccoin.lightwallet.client.model.BlockHeightUnsafe
import co.electriccoin.lightwallet.client.model.Response
import co.electriccoin.lightwallet.client.model.SendResponseUnsafe

/** Local result codes for a transaction the SDK refuses to submit. Nothing is sent. */
internal const val SUBMIT_REFUSED_WRONG_BRANCH = -9101
internal const val SUBMIT_REFUSED_EXPIRED = -9102

/**
 * Submits [rawTransaction] unless the network would reject it. With a [network], the height of the
 * server the transaction is about to go to is read first, on this same client, and a transaction built
 * for another consensus branch than the next block's, or one that has expired by then, is refused
 * locally: submitting it can only leak it. The next block is the one after the higher of that height
 * and the wallet's own tip ([walletTip], when it is known). NU7 changes the consensus branch, so a transaction signed
 * just before the activation and submitted just after it is such a case. When the height cannot be
 * read nothing is submitted.
 */
internal suspend fun CombinedWalletClient.submitTransaction(
    rawTransaction: FirstClassByteArray,
    txId: FirstClassByteArray,
    sdkFlags: SdkFlags,
    network: ZcashNetwork?,
    walletTip: suspend () -> Long? = { null }
): TransactionSubmitResult {
    val refusal =
        network?.let {
            val serverHeight =
                getLatestBlockHeight(
                    serviceMode = sdkFlags ifTor ServiceMode.Group("submit-${txId.byteArray.toHexReversed()}")
                )
            submissionRefusal(rawTransaction, txId, it, serverHeight, walletTip())
        }
    return refusal ?: submitTransaction(rawTransaction, txId, sdkFlags)
}

/**
 * The failure to return instead of submitting, or null when the transaction may be submitted. Judged at
 * the height after the higher of the server's latest block and the wallet's tip, so a server that lags
 * the wallet cannot make an old-branch transaction look valid.
 */
internal fun submissionRefusal(
    rawTransaction: FirstClassByteArray,
    txId: FirstClassByteArray,
    network: ZcashNetwork,
    serverHeight: Response<BlockHeightUnsafe>,
    walletTip: Long? = null
): TransactionSubmitResult.Failure? =
    when (serverHeight) {
        is Response.Success -> {
            val next = BlockHeight.new(maxOf(serverHeight.result.value, walletTip ?: 0L) + 1)
            when (checkSubmittable(rawTransaction.byteArray, network, next)) {
                SubmissionRefusal.WRONG_BRANCH -> {
                    refused(txId, SUBMIT_REFUSED_WRONG_BRANCH, "Built for another consensus branch than block $next")
                }

                SubmissionRefusal.EXPIRED -> {
                    refused(txId, SUBMIT_REFUSED_EXPIRED, "Expired before block $next")
                }

                else -> {
                    null
                }
            }
        }

        is Response.Failure -> {
            TransactionSubmitResult.Failure(
                txId = txId,
                grpcError = true,
                code = serverHeight.code,
                description = "Could not read the server height before submitting: ${serverHeight.description}",
                isTorFailure = serverHeight is Response.Failure.OverTor,
            )
        }
    }

/**
 * The wallet's own tip for the submission check: the highest block it has scanned, or null when that cannot be
 * read, in which case the server's height alone is judged.
 */
@Suppress("TooGenericExceptionCaught")
internal suspend fun walletTipForSubmission(backend: TypesafeBackend): Long? =
    try {
        backend.getMaxScannedHeight()?.value
    } catch (e: Exception) {
        Twig.warn(e) { "Could not read the wallet tip for the submission check" }
        null
    }

private fun refused(
    txId: FirstClassByteArray,
    code: Int,
    description: String
) = TransactionSubmitResult.Failure(txId = txId, grpcError = false, code = code, description = description)

internal suspend fun CombinedWalletClient.submitTransaction(
    rawTransaction: FirstClassByteArray,
    txId: FirstClassByteArray,
    sdkFlags: SdkFlags
): TransactionSubmitResult {
    val initialResult =
        submitTransaction(
            tx = rawTransaction.byteArray,
            serviceMode =
                sdkFlags ifTor
                    ServiceMode.Group("submit-${txId.byteArray.toHexReversed()}")
        ).toSubmitResult(txId)

    // Trust the network over the submit-side error: if the server confirms it has this tx,
    // the broadcast already landed (Zebra's MempoolError::InMempool / AlreadyQueued, zcashd's
    // already-in-chain, or any future variant). Skip the misleading failure UI.
    if (initialResult is TransactionSubmitResult.Failure && !initialResult.grpcError) {
        if (isTransactionKnownToServer(txId, sdkFlags)) {
            Twig.info {
                "SUCCESS (verified known to server): submit transaction for: ${txId.byteArray.toHexReversed()}"
            }
            return TransactionSubmitResult.Success(txId)
        }
    }

    return initialResult
}

@Suppress("TooGenericExceptionCaught")
private suspend fun CombinedWalletClient.isTransactionKnownToServer(
    txId: FirstClassByteArray,
    sdkFlags: SdkFlags
): Boolean =
    try {
        val response =
            fetchTransaction(
                txId = txId.byteArray,
                serviceMode =
                    sdkFlags ifTor
                        ServiceMode.Group("submit-${txId.byteArray.toHexReversed()}")
            )
        when (response) {
            is Response.Success -> true
            else -> false
        }
    } catch (t: Throwable) {
        Twig.warn(t) {
            "Failed to verify whether server knows tx ${txId.byteArray.toHexReversed()}; treating as unknown"
        }
        false
    }

internal fun Response<SendResponseUnsafe>.toSubmitResult(txId: FirstClassByteArray): TransactionSubmitResult =
    when (this) {
        is Response.Success -> {
            if (result.code == 0) {
                Twig.info {
                    "SUCCESS: submit transaction completed for: ${txId.byteArray.toHexReversed()}"
                }
                TransactionSubmitResult.Success(txId)
            } else {
                Twig.error {
                    "FAILURE! submit transaction ${txId.byteArray.toHexReversed()} " +
                        "completed with response: ${result.code}: ${result.message}"
                }
                TransactionSubmitResult.Failure(
                    txId = txId,
                    grpcError = false,
                    code = result.code,
                    description = result.message
                )
            }
        }

        is Response.Failure -> {
            Twig.error {
                "FAILURE! submit transaction failed with gRPC response: $code: $description"
            }
            TransactionSubmitResult.Failure(
                txId = txId,
                grpcError = true,
                code = code,
                description = description,
                isTorFailure = this is Response.Failure.OverTor,
            )
        }
    }
