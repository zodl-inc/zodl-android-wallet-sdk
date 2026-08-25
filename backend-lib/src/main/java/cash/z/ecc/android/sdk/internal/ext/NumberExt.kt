@file:Suppress("ktlint:standard:filename")

package cash.z.ecc.android.sdk.internal.ext

fun Long.isInUIntRange(): Boolean = this >= 0L && this <= UInt.MAX_VALUE.toLong()

/**
 * Validates that [height] is within the unsigned 32-bit range accepted by the Rust layer
 * before it crosses the JNI boundary.
 *
 * @throws IllegalArgumentException if [height] is outside the valid UInt range.
 */
internal fun requireValidHeight(
    height: Long,
    name: String
) {
    require(height.isInUIntRange()) { "$name $height is outside of allowed UInt range" }
}

/**
 * Validates that [value] is nonnegative before it crosses the JNI boundary.
 *
 * @throws IllegalArgumentException if [value] is negative.
 */
internal fun requireNonNegative(
    value: Long,
    name: String
) {
    require(value >= 0) { "$name $value must be nonnegative" }
}

/**
 * Validates that [value] is nonnegative before it crosses the JNI boundary.
 *
 * @throws IllegalArgumentException if [value] is negative.
 */
internal fun requireNonNegative(
    value: Int,
    name: String
) {
    require(value >= 0) { "$name $value must be nonnegative" }
}
