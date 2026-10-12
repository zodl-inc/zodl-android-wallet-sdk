package cash.z.ecc.android.sdk.internal

import cash.z.ecc.android.sdk.internal.ext.clearContents
import cash.z.ecc.android.sdk.internal.ext.useAndClear
import cash.z.ecc.android.sdk.internal.jni.JNI_UFVK_COMPONENT_ORCHARD
import cash.z.ecc.android.sdk.internal.jni.JNI_UFVK_COMPONENT_P2PKH
import cash.z.ecc.android.sdk.internal.jni.JNI_UFVK_COMPONENT_P2SH
import cash.z.ecc.android.sdk.internal.jni.JNI_UFVK_COMPONENT_SAPLING
import cash.z.ecc.android.sdk.internal.jni.JNI_UFVK_COMPONENT_UNKNOWN
import cash.z.ecc.android.sdk.internal.jni.JNI_UFVK_DESCRIPTION_INVALID
import cash.z.ecc.android.sdk.internal.model.JniMetadataKey
import cash.z.ecc.android.sdk.internal.model.JniUnifiedSpendingKey
import cash.z.ecc.android.sdk.model.AccountMetadataKey
import cash.z.ecc.android.sdk.model.UnifiedFullViewingKey
import cash.z.ecc.android.sdk.model.UnifiedFullViewingKeyComponents
import cash.z.ecc.android.sdk.model.UnifiedSpendingKey
import cash.z.ecc.android.sdk.model.ZcashNetwork
import cash.z.ecc.android.sdk.model.Zip32AccountIndex

fun Derivation.deriveUnifiedAddress(
    seed: ByteArray,
    network: ZcashNetwork,
    accountIndex: Zip32AccountIndex
): String = deriveUnifiedAddress(seed, network.id, accountIndex.index)

fun Derivation.deriveUnifiedAddress(
    viewingKey: String,
    network: ZcashNetwork,
): String = deriveUnifiedAddress(viewingKey, network.id)

/**
 * Decodes the JNI component bitmask; [JNI_UFVK_DESCRIPTION_INVALID] yields `null`.
 */
fun Derivation.describeUnifiedFullViewingKeyTypesafe(
    ufvk: UnifiedFullViewingKey,
    network: ZcashNetwork
): UnifiedFullViewingKeyComponents? {
    val bits = describeUnifiedFullViewingKey(ufvk.encoding, network.id)
    if (bits == JNI_UFVK_DESCRIPTION_INVALID) {
        return null
    }
    return UnifiedFullViewingKeyComponents(
        hasP2pkh = (bits and JNI_UFVK_COMPONENT_P2PKH) != 0,
        hasP2sh = (bits and JNI_UFVK_COMPONENT_P2SH) != 0,
        hasSapling = (bits and JNI_UFVK_COMPONENT_SAPLING) != 0,
        hasOrchard = (bits and JNI_UFVK_COMPONENT_ORCHARD) != 0,
        hasUnknownItems = (bits and JNI_UFVK_COMPONENT_UNKNOWN) != 0
    )
}

fun Derivation.deriveUnifiedSpendingKey(
    seed: ByteArray,
    network: ZcashNetwork,
    accountIndex: Zip32AccountIndex
): UnifiedSpendingKey =
    deriveUnifiedSpendingKey(seed, network.id, accountIndex.index).useAndClear {
        UnifiedSpendingKey(JniUnifiedSpendingKey(bytes = it))
    }

fun Derivation.deriveUnifiedFullViewingKey(
    usk: UnifiedSpendingKey,
    network: ZcashNetwork
): UnifiedFullViewingKey {
    val uskBytes = usk.copyBytes()
    return try {
        UnifiedFullViewingKey(
            deriveUnifiedFullViewingKey(
                JniUnifiedSpendingKey(
                    bytes = uskBytes
                ),
                network.id
            )
        )
    } finally {
        uskBytes.clearContents()
    }
}

fun Derivation.deriveUnifiedFullViewingKeysTypesafe(
    seed: ByteArray,
    network: ZcashNetwork,
    numberOfAccounts: Int
): List<UnifiedFullViewingKey> =
    deriveUnifiedFullViewingKeys(
        seed,
        network.id,
        numberOfAccounts
    ).map { UnifiedFullViewingKey(it) }

fun Derivation.deriveAccountMetadataKeyTypesafe(
    seed: ByteArray,
    network: ZcashNetwork,
    accountIndex: Zip32AccountIndex
): JniMetadataKey = deriveAccountMetadataKey(seed, network.id, accountIndex.index)

fun Derivation.derivePrivateUseMetadataKeyTypesafe(
    accountMetadataKey: AccountMetadataKey,
    ufvk: String?,
    network: ZcashNetwork,
    privateUseSubject: ByteArray
): Array<ByteArray> {
    val jniMetadataKey = accountMetadataKey.toUnsafe()
    return try {
        derivePrivateUseMetadataKey(jniMetadataKey, ufvk, network.id, privateUseSubject)
    } finally {
        jniMetadataKey.sk.clearContents()
        jniMetadataKey.chainCode.clearContents()
    }
}

/**
 * Deliberately not wrapped in [useAndClear]: the array returned by the JNI call is the derived key
 * itself and is handed to the caller as-is, so there is no transient copy to wipe here. Ownership of
 * the returned key material, including zeroing it, rests with the caller.
 */
fun Derivation.deriveArbitraryWalletKeyTypesafe(
    contextString: ByteArray,
    seed: ByteArray
): ByteArray = deriveArbitraryWalletKey(contextString, seed)

/**
 * Deliberately not wrapped in [useAndClear] for the same reason as [deriveArbitraryWalletKeyTypesafe]:
 * the returned array is the caller-owned key, not a transient copy.
 */
fun Derivation.deriveArbitraryAccountKeyTypesafe(
    contextString: ByteArray,
    seed: ByteArray,
    network: ZcashNetwork,
    accountIndex: Zip32AccountIndex
): ByteArray = deriveArbitraryAccountKey(contextString, seed, network.id, accountIndex.index)
