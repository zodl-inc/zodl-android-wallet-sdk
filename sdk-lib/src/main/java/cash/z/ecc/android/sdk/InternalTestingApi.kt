package cash.z.ecc.android.sdk

/**
 * Marks an API that exists only so internal builds can test something that must never be on in a production build.
 * Using it requires an explicit opt-in, so a call site cannot reach it by accident.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "This API is for internal testing only. Production builds must never use it."
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
annotation class InternalTestingApi
