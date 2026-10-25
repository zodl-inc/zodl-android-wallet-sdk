package cash.z.ecc.android.sdk.internal.jni

import cash.z.ecc.android.sdk.internal.model.JniAccount
import co.electriccoin.lightwallet.client.model.TreeStateUnsafe
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/**
 * Exercises the `setAccountName` JNI export against a testnet wallet holding one view-only account
 * imported from a full viewing key: a new name reads back through `getAccounts`, an emoji survives
 * the round trip, an empty name is stored as given, and an unknown account is rejected without the
 * error echoing the name.
 */
class RustBackendAccountNameTest {
    companion object {
        private const val DB_PREFIX = "account-name-"

        /** `AccountPurpose.ViewOnly.value` in sdk-lib, which backend-lib cannot depend on. */
        private const val VIEW_ONLY_PURPOSE = 1
        private const val IMPORTED_NAME = "Imported"
        private const val SECRET_NAME = "Secret vault name"

        /** The testnet checkpoint at 2,320,000, from sdk-lib's checkpoint assets. */
        private const val TREE_HEIGHT = 2320000L
        private const val TREE_HASH = "001e49cf9fbb81c26a1c5287839d40759be3c0ab0563717c2a5a945f85f04238"
        private const val TREE_TIME = 1682476862
        private const val SAPLING_TREE =
            "01bcc45378b05a226a6983e8bcc262b2c98f7f14b95e228083c49432b2b015f037001001f8a752bf5a0103a266e4270a" +
                "56b4a04377b196e27d3940cf741ab132269ad2440000000000012e45bfa0a2e6c4430dd959f8df3d4cfe4de7fe6b8278" +
                "9543dde4573a405e7c5001351a9a78dd63c0f38fd48f4de7ecd9bfa50e5a7c6271757b236af70307d2242901c3242178" +
                "963365ebea59f9424fbf8b6a59866c62a988bda4073072ab1db00e2f0178c3b52b85e731ba1c36be42e3c3e7a45297d3" +
                "239c9981cc32cd7c3734a80e6a01ec2e0fc55d1b853315b012a4fcdd22c98be41935f142c011b87de9c1eb12e6090001" +
                "5f8b9205e0514fa235d75c150b87e23866b882b39786852d1ab42aab11d31a4a0117ddeb3a5f8d2f6b2d0a07f28f01ab" +
                "25e03a05a9319275bb86d72fcaef6fc01501f08f39275112dd8905b854170b7f247cf2df18454d4fa94e6e4f9320cca0" +
                "5f24011f8322ef806eb2430dc4a7a41c1b344bea5be946efc7b4349c1c9edb14ff9d39"
        private const val ORCHARD_TREE =
            "01aab18f0ebd1846ab4e9fa7aaf906ea626d73a4df447844e03a0a9e72010cc80b001f0000016d7fe0fd034dd0364156" +
                "b3ec39089a58bff82238bcd09c6128e7a1b1c77e740401559d556ed081623feaa18937b184634c24e3bbd97ed71095b2" +
                "e39f8042b7b11601459572ae40b723d969145bdd5d2ad63a78ae7ea92b295e46f1b6db3551a4500d01a4569774206124" +
                "be5db2269fa9d2e52b4d55db2f3e9e8d4636a5d375c2d5a3340000000000013bf8b923e4187754e85175748d9cce4824" +
                "a6787e4258977b5bfe1ba59012c032000001f3bbdc62260c4fca5c84bf3487246d4542da48eeeec8ec40c1029b6908ee" +
                "f83c00000000000000000000000000000000"
    }

    @Test
    fun a_view_only_import_takes_any_name() =
        runTest {
            val backend = TestnetBackendFixture.newBackend(DB_PREFIX)
            val account = importViewOnly(backend)

            listOf("Savings", "Vault 🔐", "").forEach { name ->
                backend.setAccountName(account.accountUuid, name)

                assertEquals(name, nameOf(backend, account), "The name \"$name\" did not read back as given")
            }
        }

    @Test
    fun an_unknown_account_is_rejected_without_echoing_the_name() =
        runTest {
            val backend = TestnetBackendFixture.newBackend(DB_PREFIX)
            importViewOnly(backend)
            val unknown = ByteArray(16) { 0x7F }

            val error = assertFailsWith<RuntimeException> { backend.setAccountName(unknown, SECRET_NAME) }

            assertFalse(error.message.orEmpty().contains(SECRET_NAME))
        }

    private suspend fun nameOf(
        backend: RustBackend,
        account: JniAccount
    ): String? = backend.getAccounts().single { it.accountUuid.contentEquals(account.accountUuid) }.accountName

    private suspend fun importViewOnly(backend: RustBackend): JniAccount =
        backend.importAccountUfvk(
            accountName = IMPORTED_NAME,
            keySource = null,
            ufvk = TestnetBackendFixture.deriveUfvk(),
            treeState =
                TreeStateUnsafe
                    .fromParts(
                        height = TREE_HEIGHT,
                        hash = TREE_HASH,
                        time = TREE_TIME,
                        saplingTree = SAPLING_TREE,
                        orchardTree = ORCHARD_TREE,
                        ironwoodTree = null
                    ).encoded,
            recoverUntil = null,
            purpose = VIEW_ONLY_PURPOSE,
            seedFingerprint = null,
            zip32AccountIndex = null,
        )
}
