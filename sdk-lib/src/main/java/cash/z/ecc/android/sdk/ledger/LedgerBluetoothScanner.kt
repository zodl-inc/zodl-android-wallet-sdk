package cash.z.ecc.android.sdk.ledger

import android.annotation.SuppressLint
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import cash.z.ecc.android.sdk.exception.LedgerException
import cash.z.ecc.android.sdk.internal.Twig
import cash.z.ecc.android.sdk.internal.ledger.ble.bluetoothUnauthorized
import cash.z.ecc.android.sdk.internal.ledger.ble.usableBluetoothAdapter
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlin.time.Duration

/**
 * Finds Ledger devices advertising over Bluetooth LE.
 *
 * The app must hold the Bluetooth permissions for the running API level; see `docs/Ledger.md`. The
 * SDK's manifest declares none, so an app that does not use Ledger devices does not acquire them.
 *
 * A device already connected to another app or phone does not advertise and is not found; the user
 * has to disconnect it first.
 *
 * @param context Any context; the application context is kept.
 */
class LedgerBluetoothScanner(
    context: Context
) {
    private val context = context.applicationContext

    // `MissingPermission` is suppressed on [devices]: the permissions are checked at runtime before the
    // scan starts, and the SDK's manifest declares none itself.

    /**
     * Scans for Ledger devices until the flow's collector is cancelled.
     *
     * Each emission is every device found so far, one entry per device with its latest signal
     * strength, strongest first. Only the latest list matters, so a slow collector skips intermediate
     * ones.
     *
     * @throws LedgerException.BluetoothUnavailable if the device has no Bluetooth LE, or the scan
     *         could not be started.
     * @throws LedgerException.BluetoothUnauthorized if a required permission is not granted.
     * @throws LedgerException.BluetoothDisabled if Bluetooth is off.
     */
    @SuppressLint("MissingPermission")
    fun devices(): Flow<List<LedgerBluetoothDevice>> =
        callbackFlow {
            val scanner =
                usableBluetoothAdapter(context).bluetoothLeScanner
                    ?: throw LedgerException.BluetoothDisabled()
            val found = LinkedHashMap<String, LedgerBluetoothDevice>()

            fun record(result: ScanResult) {
                val device = result.toLedgerDevice() ?: return
                val snapshot =
                    synchronized(found) {
                        found[device.identifier] = device
                        found.values.sortedByDescending { it.rssi }
                    }
                trySend(snapshot)
            }
            val callback =
                object : ScanCallback() {
                    override fun onScanResult(
                        callbackType: Int,
                        result: ScanResult
                    ) = record(result)

                    override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::record)

                    override fun onScanFailed(errorCode: Int) {
                        Twig.warn { "Ledger Bluetooth scan failed with code $errorCode" }
                        close(LedgerException.BluetoothUnavailable(scanErrorCode = errorCode))
                    }
                }
            val filters =
                LedgerDeviceModel.serviceUuids.map { uuid ->
                    ScanFilter.Builder().setServiceUuid(ParcelUuid(uuid)).build()
                }
            val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
            try {
                scanner.startScan(filters, settings, callback)
            } catch (e: SecurityException) {
                throw bluetoothUnauthorized(context, e)
            }
            awaitClose {
                runCatching { scanner.stopScan(callback) }
            }
        }.conflate()

    /**
     * Connects to [device]; see [LedgerBluetoothTransport.connect].
     *
     * @param device A device from [devices].
     * @param connectTimeout How long the whole setup may take, pairing included.
     */
    suspend fun connect(
        device: LedgerBluetoothDevice,
        connectTimeout: Duration = LedgerBluetoothTransport.DEFAULT_CONNECT_TIMEOUT
    ): LedgerBluetoothTransport = LedgerBluetoothTransport.connect(context, device, connectTimeout)

    private fun ScanResult.toLedgerDevice(): LedgerBluetoothDevice? {
        val (model, _) =
            scanRecord
                ?.serviceUuids
                ?.firstNotNullOfOrNull { LedgerDeviceModel.forServiceUuid(it.uuid) }
                ?: return null
        return LedgerBluetoothDevice(
            model = model,
            name = scanRecord?.deviceName,
            identifier = device.address,
            rssi = rssi
        )
    }
}
