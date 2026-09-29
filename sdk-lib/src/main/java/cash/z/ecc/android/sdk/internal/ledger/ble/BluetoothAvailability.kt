package cash.z.ecc.android.sdk.internal.ledger.ble

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import cash.z.ecc.android.sdk.exception.LedgerException

/**
 * The runtime permissions Bluetooth LE scanning and connecting need on this API level.
 *
 * API 31 and later: `BLUETOOTH_SCAN` (declared with `neverForLocation`) and `BLUETOOTH_CONNECT`.
 * Earlier: `BLUETOOTH`, `BLUETOOTH_ADMIN` and `ACCESS_FINE_LOCATION`, which scanning requires there.
 */
internal fun requiredBluetoothPermissions(): List<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        listOf(
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
    }

/**
 * The permissions of [requiredBluetoothPermissions] not granted to the app now.
 */
internal fun missingBluetoothPermissions(context: Context): List<String> =
    requiredBluetoothPermissions().filter {
        ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
    }

/**
 * [LedgerException.BluetoothUnauthorized] for a [SecurityException] from the Bluetooth stack. It names
 * the permissions not granted now, or every required one when all of them read as granted, so the app
 * always has something to request.
 */
internal fun bluetoothUnauthorized(
    context: Context,
    cause: SecurityException
): LedgerException.BluetoothUnauthorized =
    LedgerException.BluetoothUnauthorized(
        missingPermissions = missingBluetoothPermissions(context).ifEmpty { requiredBluetoothPermissions() },
        cause = cause
    )

/**
 * The device's Bluetooth adapter, once Bluetooth LE is known to be present, permitted and on.
 *
 * @throws LedgerException.BluetoothUnavailable if the device has no Bluetooth LE.
 * @throws LedgerException.BluetoothUnauthorized if a required permission is not granted.
 * @throws LedgerException.BluetoothDisabled if Bluetooth is off.
 */
@Suppress("ThrowsCount")
internal fun usableBluetoothAdapter(context: Context): BluetoothAdapter {
    val adapter =
        context
            .takeIf { it.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE) }
            ?.getSystemService(BluetoothManager::class.java)
            ?.adapter
            ?: throw LedgerException.BluetoothUnavailable()
    val missing = missingBluetoothPermissions(context)
    if (missing.isNotEmpty()) {
        throw LedgerException.BluetoothUnauthorized(missingPermissions = missing)
    }
    if (!adapter.isEnabled) {
        throw LedgerException.BluetoothDisabled()
    }
    return adapter
}
