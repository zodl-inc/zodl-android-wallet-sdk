package cash.z.ecc.android.sdk.internal

import cash.z.ecc.android.sdk.exception.InitializeException

/**
 * Matches an `import_account_ufvk` or `findAccountKeyOverlap` failure against the markers the Rust backend tags two
 * distinguishable failures with: a transient, retryable cross-pool checkpoint-parity failure, and
 * a key that collides with an existing account.
 *
 * Mirrors `IMPORT_CHECKPOINTS_NOT_READY_MARKER` / `map_import_account_error` in
 * `backend-lib/src/main/rust/lib.rs`, which prefixes the message of the mapped
 * `anyhow::Error` with this exact string when `import_account_ufvk` fails due to
 * `SqliteClientError::CorruptedData` raised by `rewind_to_chain_state`'s cross-pool checkpoint
 * parity check, and `IMPORT_ACCOUNT_COLLISION_MARKER`, which prefixes it when the key shares a
 * viewing key component with an existing account without upgrading it.
 */
internal object ImportAccountErrors {
    private const val CHECKPOINTS_NOT_READY_MARKER = "ImportAccountCheckpointsNotReady"

    private const val ACCOUNT_COLLISION_MARKER = "ImportAccountCollision"

    /**
     * @return `true` if [t]'s message indicates the transient checkpoint-parity condition
     * described in [ImportAccountErrors], `false` otherwise (including when [t] has no
     * message).
     */
    fun isCheckpointsNotReady(t: Throwable): Boolean = t.message?.startsWith(CHECKPOINTS_NOT_READY_MARKER) == true

    /**
     * @return `true` if [t]'s message indicates that the imported key collides with an existing
     * account, `false` otherwise (including when [t] has no message).
     */
    fun isAccountCollision(t: Throwable): Boolean = t.message?.startsWith(ACCOUNT_COLLISION_MARKER) == true

    /**
     * Maps an `importAccountUfvk` backend failure to the [InitializeException] the synchronizers
     * throw for it.
     */
    fun toImportAccountException(t: Throwable): InitializeException =
        when {
            isCheckpointsNotReady(t) -> InitializeException.ImportAccountCheckpointsNotReadyException(t)
            isAccountCollision(t) -> InitializeException.ImportAccountCollisionException(t)
            else -> InitializeException.ImportAccountException(t)
        }

    /**
     * Maps a `findAccountKeyOverlap` backend failure to the [InitializeException] the synchronizers
     * throw for it: a key sharing components with more than one account fails the lookup with the
     * collision marker, as its import would, and maps to the same exception the import throws.
     */
    fun toFindAccountSharingKeyComponentsException(t: Throwable): InitializeException =
        if (isAccountCollision(t)) {
            InitializeException.ImportAccountCollisionException(t)
        } else {
            InitializeException.FindAccountSharingKeyComponentsException(t)
        }
}
