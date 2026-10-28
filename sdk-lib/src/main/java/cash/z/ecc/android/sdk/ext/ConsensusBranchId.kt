package cash.z.ecc.android.sdk.ext

import java.util.Locale

/**
 * Helper class for converting/displaying consensus branch ids. Activation height is intentionally
 * omitted since this is not the source of truth for branch information but rather a tool for
 * printing that information to users.
 */
@Suppress("MagicNumber")
enum class ConsensusBranchId(
    val displayName: String,
    val id: Long,
    val hexId: String
) {
    // TODO [#679]: see if we can find a way to not rely on this separate source of truth (either stop converting from
    //  hex to display name in the apps or use Rust to get this info)
    // TODO [#679]: https://github.com/zcash/zcash-android-wallet-sdk/issues/679
    SPROUT("Sprout", 0, "0"),
    OVERWINTER("Overwinter", 0x5ba8_1b19, "5ba81b19"),
    SAPLING("Sapling", 0x76b8_09bb, "76b809bb"),
    BLOSSOM("Blossom", 0x2bb4_0e60, "2bb40e60"),
    HEARTWOOD("Heartwood", 0xf5b9_230b, "f5b9230b"),
    CANOPY("Canopy", 0xe9ff_75a6, "e9ff75a6"),
    NU5("NU5", 0xc2d6_d0b4, "c2d6d0b4"),
    NU6("NU6", 0xc8e7_1055, "c8e71055"),
    NU6_1("NU6.1", 0x4dec_4df0, "4dec4df0"),
    NU6_2("NU6.2", 0x5437_f330, "5437f330"),
    NU6_3("NU6.3", 0x37a5_165b, "37a5165b"),
    NU7("NU7", 0x7719_0ad9, "77190ad9");

    override fun toString(): String = displayName

    companion object {
        fun fromName(name: String): ConsensusBranchId? = entries.firstOrNull { it.displayName.equals(name, true) }

        fun fromId(id: Long): ConsensusBranchId? = entries.firstOrNull { it.id == id }

        fun fromHex(hex: String): ConsensusBranchId? =
            entries.firstOrNull { branch ->
                hex.lowercase(Locale.US).replace("_", "").replaceFirst("0x", "").let { sanitized ->
                    branch.hexId.equals(sanitized, true)
                }
            }
    }
}
