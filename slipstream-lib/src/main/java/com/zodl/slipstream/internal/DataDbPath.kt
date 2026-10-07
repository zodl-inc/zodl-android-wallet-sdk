package com.zodl.slipstream.internal

import cash.z.ecc.android.sdk.model.ZcashNetwork
import java.io.File

/**
 * The D5-critical path formula (`SDK_ADAPTER_PLAN.md` T10, R57 `getExistingDataDbFilePath` /
 * `getWalletDbPathForVoting` / C3 `erase`) - MUST match the upstream SDK's
 * `DatabaseCoordinator`/`Files`/`DB_DATA_NAME` byte-for-byte, or flag-flip breaks:
 * `<no_backup>/co.electricoin.zcash/<aliasPrefix><networkName>_data.sqlite3`, `aliasPrefix =
 * alias.endsWith('_') ? alias : "${alias}_"`, `networkName` = lowercase network name. Ships as a
 * pure `File`-returning function (the no-backup root injected as a plain [File]) so it is
 * JVM-testable without an Android context (`SDK_ADAPTER_PLAN.md` law 5).
 */
internal object DataDbPath {
    private const val NO_BACKUP_SUBDIRECTORY = "co.electricoin.zcash"
    private const val DB_DATA_NAME = "data.sqlite3"

    private const val FS_BLOCK_DB_ROOT_NAME = "fs_cache"
    private const val PENDING_TRANSACTIONS_NAME = "pending_transactions.sqlite3"
    private val DATABASE_FILE_SUFFIXES = listOf("", "-journal", "-wal", "-shm")

    fun dataDbFile(
        noBackupRoot: File,
        alias: String,
        network: ZcashNetwork
    ): File = walletFile(noBackupRoot, alias, network, DB_DATA_NAME)

    /**
     * The files of the wallet at [alias] and [network] that only the upstream SDK's own synchronizer
     * (`SdkSynchronizer`) creates, never this engine, named as `DatabaseCoordinator` names them: its
     * compact block cache directory, and its pending transactions database with its journal, WAL and
     * shared-memory files. `SdkSynchronizer` creates the block cache for every wallet it opens, so
     * finding any of these tells a wallet that `SdkSynchronizer` once ran under [alias] apart from one
     * only this engine ran.
     */
    fun legacyOnlyFiles(
        noBackupRoot: File,
        alias: String,
        network: ZcashNetwork
    ): List<File> {
        val pending = walletFile(noBackupRoot, alias, network, PENDING_TRANSACTIONS_NAME)
        return listOf(walletFile(noBackupRoot, alias, network, FS_BLOCK_DB_ROOT_NAME)) +
            DATABASE_FILE_SUFFIXES.map { File("${pending.path}$it") }
    }

    private fun walletFile(
        noBackupRoot: File,
        alias: String,
        network: ZcashNetwork,
        name: String
    ): File {
        val aliasPrefix = if (alias.endsWith("_")) alias else "${alias}_"
        val networkName = network.networkName.lowercase()
        return File(File(noBackupRoot, NO_BACKUP_SUBDIRECTORY), "$aliasPrefix${networkName}_$name")
    }
}
