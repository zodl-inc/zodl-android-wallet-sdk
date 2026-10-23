@file:Suppress("MaxLineLength")

package com.zodl.slipstream

/**
 * How [SlipstreamSynchronizer.Companion.new] provisions the account behind a handle. Unpacked
 * internally from the public `AccountCreateSetup?`/`WalletInitMode` pair that
 * [SlipstreamSynchronizer.Companion.new] accepts (mirroring `Synchronizer.new`'s own parameters,
 * `KOTLIN_ROSETTA.md` row C1) - callers never construct this type directly.
 */
internal sealed interface SlipstreamWalletSetup {
    /** Seed never crosses the boundary - it is used ONCE, host-side, to derive the UFVK. */
    data class FromSeed(
        val seed: ByteArray
    ) : SlipstreamWalletSetup

    /** data.db already has the account (flag-flip from their engine, DECISIONS.md D5). Keyless start. */
    data object ExistingAccount : SlipstreamWalletSetup
}
