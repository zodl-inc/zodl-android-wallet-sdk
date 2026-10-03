package cash.z.ecc.android.sdk.internal.jni

import cash.z.ecc.android.sdk.internal.GiftCardLinks
import cash.z.ecc.android.sdk.internal.model.JniGiftCard

class RustGiftCardTool private constructor() : GiftCardLinks {
    @Throws(GiftCardLinkException::class)
    override fun parse(link: String): JniGiftCard = parseGiftCardLink(link)

    companion object {
        suspend fun new(): GiftCardLinks {
            RustBackend.loadLibrary()
            return RustGiftCardTool()
        }

        @JvmStatic
        private external fun parseGiftCardLink(input: String): JniGiftCard
    }
}
