package cash.z.ecc.android.sdk.model

/**
 * The components a [UnifiedFullViewingKey] carries, as reported by
 * [cash.z.ecc.android.sdk.tool.DerivationTool.describeUnifiedFullViewingKey].
 *
 * A [ZIP 316] key holds at most one transparent item, either a P2PKH key or a P2SH viewing key
 * item, so [hasP2pkh] and [hasP2sh] are never both `true`. They are reported separately because
 * only a P2PKH key gives a transparent account its addresses.
 *
 * [ZIP 316]: https://zips.z.cash/zip-0316
 *
 * Further components may be added in a later release. As this is a data class, adding one changes
 * its constructor, `copy` and `componentN` functions, so construct it, copy it or destructure it only
 * where recompiling against a newer SDK is acceptable; reading its properties stays compatible.
 *
 * @param hasP2pkh the key has a transparent P2PKH component.
 * @param hasP2sh the key has a transparent P2SH viewing key item.
 * @param hasSapling the key has a Sapling component.
 * @param hasOrchard the key has an Orchard component.
 * @param hasUnknownItems the key has at least one item of a type this SDK does not recognize.
 */
data class UnifiedFullViewingKeyComponents(
    val hasP2pkh: Boolean,
    val hasP2sh: Boolean,
    val hasSapling: Boolean,
    val hasOrchard: Boolean,
    val hasUnknownItems: Boolean
) {
    /**
     * Whether the key has any transparent component, P2PKH or P2SH.
     */
    val hasTransparent: Boolean
        get() = hasP2pkh || hasP2sh
}
