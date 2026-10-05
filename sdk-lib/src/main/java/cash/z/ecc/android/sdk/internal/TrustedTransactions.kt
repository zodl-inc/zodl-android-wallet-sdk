package cash.z.ecc.android.sdk.internal

import cash.z.ecc.android.sdk.model.RawTransaction
import cash.z.ecc.android.sdk.model.TransactionId

/**
 * Stores [rawTransaction] in this wallet and marks it as trusted (ZIP 315), so its outputs are
 * spendable after the trusted number of confirmations instead of the untrusted one. This is how
 * a transaction authored on this device by another wallet, e.g. the sweep of a gift card, is
 * recorded in the wallet that receives it. See `Synchronizer.recordTrustedTransaction`, which
 * also explains when trusting such a transaction is sound.
 *
 * The transaction is stored with no mined height: scanning fills it in once the transaction is
 * mined. Trust is only set once the stored transaction's id has been checked against [txId], so
 * a caller mixing up ids cannot trust the wrong transaction.
 *
 * @throws IllegalArgumentException if [txId] is not the id of [rawTransaction]. The transaction
 * has been stored by then, as untrusted.
 */
internal suspend fun TypesafeBackend.recordTrustedTransaction(
    rawTransaction: RawTransaction,
    txId: TransactionId
) {
    val storedTxId = decryptAndStoreTransaction(rawTransaction.data, minedHeight = null)
    require(storedTxId == txId.value) {
        "txId does not match the transaction" // $NON-NLS
    }
    setTransactionTrust(txId.value.byteArray, trusted = true)
}
