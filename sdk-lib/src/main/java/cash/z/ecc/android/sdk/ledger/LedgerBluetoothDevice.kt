package cash.z.ecc.android.sdk.ledger

/**
 * A Ledger device found by [LedgerBluetoothScanner].
 *
 * @param model The device model, from the service it advertises.
 * @param name The advertised name, if any (the name the user gave the device).
 * @param identifier The device's Bluetooth address, which [LedgerBluetoothTransport.connect] takes.
 *        A stable hardware identifier: do not log it or send it anywhere.
 * @param rssi The received signal strength of the advertisement, in dBm.
 */
data class LedgerBluetoothDevice(
    val model: LedgerDeviceModel,
    val name: String?,
    val identifier: String,
    val rssi: Int
) {
    // Override to keep the hardware address out of logs
    override fun toString() = "LedgerBluetoothDevice(model=$model, name=$name, rssi=$rssi)"
}
