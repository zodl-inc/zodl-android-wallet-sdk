package cash.z.ecc.android.sdk.ledger

import java.util.UUID

/**
 * The Ledger devices that speak Bluetooth LE.
 *
 * The GATT identifiers are LedgerHQ's own, from `device-sdk-ts`,
 * `packages/device-management-kit/src/api/device-model/data/StaticDeviceModelDataSource.ts` at
 * commit `2c92958de2c1e0318cc68551871ec74044106b54`. Every model follows the pattern
 * `13d63400-2c97-XXXX-000N-4c6564676572`, with `N` = 0 for the service, 1 for the notify
 * characteristic, 2 for the write characteristic and 3 for the write-without-response one. The
 * Nano Gen5 advertises either of two identifier sets (the second added in `df480c2`).
 *
 * @param productName The name Ledger markets the model under.
 */
enum class LedgerDeviceModel(
    val productName: String,
    internal val bleSpecs: List<LedgerBleSpec>
) {
    NANO_X("Ledger Nano X", listOf(LedgerBleSpec.forModelCode("0004"))),
    STAX("Ledger Stax", listOf(LedgerBleSpec.forModelCode("6004"))),
    FLEX("Ledger Flex", listOf(LedgerBleSpec.forModelCode("3004"))),
    NANO_GEN5("Ledger Nano Gen5", listOf(LedgerBleSpec.forModelCode("8004"), LedgerBleSpec.forModelCode("9004")));

    internal companion object {
        /** Every service identifier a Ledger advertises, for scan filters. */
        val serviceUuids: List<UUID> = entries.flatMap { model -> model.bleSpecs.map { it.serviceUuid } }

        /** The model and identifier set a service identifier belongs to. */
        fun forServiceUuid(uuid: UUID): Pair<LedgerDeviceModel, LedgerBleSpec>? =
            entries.firstNotNullOfOrNull { model ->
                model.bleSpecs.firstOrNull { it.serviceUuid == uuid }?.let { model to it }
            }
    }
}

/**
 * One Ledger GATT service and its three characteristics.
 */
internal data class LedgerBleSpec(
    val serviceUuid: UUID,
    val notifyUuid: UUID,
    val writeUuid: UUID,
    val writeCmdUuid: UUID
) {
    companion object {
        private const val ROLE_SERVICE = 0
        private const val ROLE_NOTIFY = 1
        private const val ROLE_WRITE = 2
        private const val ROLE_WRITE_CMD = 3

        fun forModelCode(code: String) =
            LedgerBleSpec(
                serviceUuid = uuid(code, ROLE_SERVICE),
                notifyUuid = uuid(code, ROLE_NOTIFY),
                writeUuid = uuid(code, ROLE_WRITE),
                writeCmdUuid = uuid(code, ROLE_WRITE_CMD)
            )

        private fun uuid(
            code: String,
            role: Int
        ) = UUID.fromString("13d63400-2c97-$code-000$role-4c6564676572")
    }
}
