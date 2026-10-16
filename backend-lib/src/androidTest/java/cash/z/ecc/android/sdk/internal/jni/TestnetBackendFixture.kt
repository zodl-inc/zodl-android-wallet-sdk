package cash.z.ecc.android.sdk.internal.jni

import cash.z.ecc.android.bip39.Mnemonics
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals

/** A fresh, account-less testnet wallet and the full viewing key of the test seed, for the JNI tests. */
internal object TestnetBackendFixture {
    const val SEED_PHRASE =
        "kitchen renew wide common vague fold vacuum tilt amazing pear square gossip jewel month tree shock scan" +
            " alpha just spot fluid toilet view dinner"
    const val TESTNET_ID = 0
    const val INIT_DATA_DB_SUCCESS = 0

    /** A testnet backend over an initialized, empty data db in a new temp directory named after [prefix]. */
    suspend fun newBackend(prefix: String): RustBackend {
        val root = createTempDirectory(prefix).toFile()
        val backend =
            RustBackend.new(
                fsBlockDbRoot = root.resolve("fs_cache").apply { mkdirs() },
                dataDbFile = root.resolve("data.sqlite3"),
                saplingSpendFile = root.resolve("sapling-spend.params"),
                saplingOutputFile = root.resolve("sapling-output.params"),
                zcashNetworkId = TESTNET_ID
            )
        assertEquals(INIT_DATA_DB_SUCCESS, backend.initDataDb(seed = null))
        return backend
    }

    /** The first account's unified full viewing key of [SEED_PHRASE] on [networkId]. */
    suspend fun deriveUfvk(networkId: Int = TESTNET_ID): String =
        RustDerivationTool
            .new()
            .deriveUnifiedFullViewingKeys(
                Mnemonics.MnemonicCode(SEED_PHRASE).toEntropy(),
                networkId = networkId,
                numberOfAccounts = 1
            ).single()
}
