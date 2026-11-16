package co.electriccoin.lightwallet.client

/**
 * Marks the parts of the OHTTP configuration that only the SDK itself may call: an app installs the
 * configuration with `cash.z.ecc.android.sdk.Ohttp.configure`, which also installs it in the native library.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "Internal to the SDK. Apps install the OHTTP configuration with cash.z.ecc.android.sdk.Ohttp.configure."
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
annotation class InternalOhttpApi
