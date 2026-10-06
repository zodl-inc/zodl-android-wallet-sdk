package cash.z.ecc.android.sdk.internal.ledger.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.content.ContextCompat
import cash.z.ecc.android.sdk.exception.LedgerException
import cash.z.ecc.android.sdk.internal.Twig
import cash.z.ecc.android.sdk.ledger.LedgerBleSpec
import cash.z.ecc.android.sdk.ledger.LedgerDeviceModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A [LedgerBleLink] over Android's [BluetoothGatt].
 *
 * Android's GATT client runs one operation at a time and reports each through a callback; starting a
 * second before the first's callback arrives fails silently. Every operation here therefore holds
 * `operationMutex` from the call until its callback completes the pending operation.
 *
 * The connection is set up the way LedgerHQ's Android transport (`device-sdk-ts`,
 * `packages/transport/rn-ble`, over `react-native-ble-plx`) sets it up: connect, raise the connection
 * priority, request a large ATT MTU before discovery, discover services, subscribe to the notify
 * characteristic, and write to the write-without-response characteristic when the device offers it.
 * One difference: the subscription's descriptor write is awaited before anything else is written,
 * because the firmware drops writes until notifications are enabled on an encrypted link.
 *
 * Ledger requires an encrypted, bonded link. The first protected operation on an unbonded device
 * starts Android's pairing flow; a descriptor write refused for authentication while pairing runs is
 * retried once the bond completes, and a pairing that ends unbonded is
 * [LedgerException.PairingRefused].
 *
 * Nothing written or received is logged. Bluetooth permissions are checked by
 * [usableBluetoothAdapter] before a link is opened, which is why `MissingPermission` is suppressed:
 * the SDK's manifest declares none.
 */
@SuppressLint("MissingPermission")
@Suppress("TooManyFunctions")
internal class AndroidGattLink private constructor(
    private val context: Context,
    private val device: BluetoothDevice
) : LedgerBleLink {
    private val operationMutex = Mutex()
    private val pendingOperation = AtomicReference<PendingOperation?>(null)
    private val connected = CompletableDeferred<Unit>()
    private val closed = AtomicBoolean(false)
    private val bondState = MutableStateFlow(device.bondState)
    private val notificationChannel = Channel<ByteArray>(Channel.UNLIMITED)

    private var gatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT

    /** The model whose service the device offers; set once the link is set up. */
    var model: LedgerDeviceModel? = null
        private set

    override val notifications: ReceiveChannel<ByteArray> = notificationChannel

    private val bondReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent
            ) {
                val changed = intentDevice(intent) ?: return
                if (changed.address == device.address) {
                    bondState.value =
                        intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                }
            }
        }

    private val callback =
        object : BluetoothGattCallback() {
            override fun onConnectionStateChange(
                gatt: BluetoothGatt,
                status: Int,
                newState: Int
            ) {
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    connected.complete(Unit)
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Twig.info { "Ledger BLE link disconnected (status $status)" }
                    dropped()
                }
            }

            override fun onMtuChanged(
                gatt: BluetoothGatt,
                mtu: Int,
                status: Int
            ) {
                Twig.debug { "Ledger BLE ATT MTU is $mtu (status $status)" }
                completeOperation(Operation.MTU, status)
            }

            override fun onServicesDiscovered(
                gatt: BluetoothGatt,
                status: Int
            ) {
                completeOperation(Operation.DISCOVER_SERVICES, status)
            }

            override fun onDescriptorWrite(
                gatt: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int
            ) {
                completeOperation(Operation.WRITE_DESCRIPTOR, status)
            }

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                completeOperation(Operation.WRITE_CHARACTERISTIC, status)
            }

            // Delivered on API 32 and earlier. From API 33 the three-argument overload below is called
            // instead, and its base implementation forwards here, which is why it is not called.
            @Deprecated("Deprecated in API 33")
            @Suppress("DEPRECATION")
            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic
            ) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    characteristic.value?.let { notificationChannel.trySend(it.copyOf()) }
                }
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray
            ) {
                notificationChannel.trySend(value.copyOf())
            }
        }

    override suspend fun write(frame: ByteArray) {
        val characteristic = writeCharacteristic ?: throw LedgerException.Disconnected()
        val status =
            operation(Operation.WRITE_CHARACTERISTIC, OPERATION_TIMEOUT) { gatt ->
                writeCharacteristic(gatt, characteristic, frame)
            }
        if (status != BluetoothGatt.GATT_SUCCESS) {
            throw if (status.isAuthenticationFailure()) {
                LedgerException.PairingRefused(reason = "the device refused a write on an unauthenticated link")
            } else {
                LedgerException.Disconnected()
            }
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            runCatching { context.unregisterReceiver(bondReceiver) }
            gatt?.let {
                runCatching { it.disconnect() }
                runCatching { it.close() }
            }
            dropped()
        }
    }

    private fun dropped() {
        val disconnected = LedgerException.Disconnected()
        pendingOperation.getAndSet(null)?.result?.completeExceptionally(disconnected)
        connected.completeExceptionally(disconnected)
        notificationChannel.close(disconnected)
    }

    private suspend fun connect() {
        ContextCompat.registerReceiver(
            context,
            bondReceiver,
            IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        gatt =
            device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
                ?: throw LedgerException.ConnectionFailed(reason = "the Bluetooth stack refused the connection")
        try {
            connected.await()
        } catch (e: LedgerException.Disconnected) {
            throw LedgerException.ConnectionFailed(reason = "the device disconnected while connecting", cause = e)
        }
    }

    /**
     * Connects and sets up the link. A GATT operation that times out on its own is
     * [LedgerException.ConnectionFailed]; the enclosing connect timeout, and the caller's own
     * cancellation, propagate as they are.
     */
    private suspend fun connectAndSetUp() {
        try {
            connect()
            setUp()
        } catch (e: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            throw LedgerException.ConnectionFailed(
                reason = "a Bluetooth operation timed out while connecting",
                cause = e
            )
        }
    }

    @Suppress("ThrowsCount")
    private suspend fun setUp() {
        val gatt = gatt ?: throw LedgerException.Disconnected()
        gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
        requestLargeMtu()

        val discovery = operation(Operation.DISCOVER_SERVICES, OPERATION_TIMEOUT) { it.discoverServices() }
        if (discovery != BluetoothGatt.GATT_SUCCESS) {
            throw LedgerException.ConnectionFailed(reason = "service discovery failed with status $discovery")
        }
        val (model, spec) =
            gatt.services.firstNotNullOfOrNull { LedgerDeviceModel.forServiceUuid(it.uuid) }
                ?: throw LedgerException.DeviceNotFound()
        val service = gatt.getService(spec.serviceUuid) ?: throw LedgerException.DeviceNotFound()
        chooseWriteCharacteristic(
            writeCmd = service.getCharacteristic(spec.writeCmdUuid),
            write = service.getCharacteristic(spec.writeUuid)
        )
        subscribe(gatt, service.getCharacteristic(spec.notifyUuid), spec)
        this.model = model
    }

    /**
     * Asks for the ATT MTU LedgerHQ's transport asks for; the firmware caps it at 156. A stack that
     * refuses the request or never answers it leaves the default, which the Ledger MTU handshake then
     * reports. A [SecurityException] propagates, for [open] to report as
     * [LedgerException.BluetoothUnauthorized], and so does the caller's cancellation, the enclosing
     * connect timeout included.
     */
    @Suppress("SwallowedException")
    private suspend fun requestLargeMtu() {
        try {
            operation(Operation.MTU, MTU_TIMEOUT) { it.requestMtu(REQUESTED_ATT_MTU) }
        } catch (e: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            Twig.debug { "Ledger BLE ATT MTU request was not answered; keeping the default" }
        } catch (e: LedgerException) {
            Twig.debug { "Ledger BLE ATT MTU request failed (${e.javaClass.simpleName}); keeping the default" }
        }
    }

    private fun chooseWriteCharacteristic(
        writeCmd: BluetoothGattCharacteristic?,
        write: BluetoothGattCharacteristic?
    ) {
        // LedgerHQ's transports prefer write-without-response for throughput and fall back to write.
        when {
            writeCmd != null && writeCmd.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0 -> {
                writeCharacteristic = writeCmd
                writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            }

            write != null && write.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0 -> {
                writeCharacteristic = write
                writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            }

            else -> {
                throw LedgerException.ConnectionFailed(reason = "the device offers no write characteristic")
            }
        }
    }

    @Suppress("ThrowsCount")
    private suspend fun subscribe(
        gatt: BluetoothGatt,
        notify: BluetoothGattCharacteristic?,
        spec: LedgerBleSpec
    ) {
        if (notify == null || !gatt.setCharacteristicNotification(notify, true)) {
            throw LedgerException.ConnectionFailed(reason = "the device offers no notify characteristic")
        }
        val descriptor =
            notify.getDescriptor(CLIENT_CHARACTERISTIC_CONFIGURATION)
                ?: throw LedgerException.ConnectionFailed(reason = "the notify characteristic cannot be subscribed to")
        var status = enableNotifications(descriptor)
        if (status.isAuthenticationFailure()) {
            // Android starts pairing on the first protected operation; wait for the user to finish it.
            Twig.info { "Ledger BLE subscription needs pairing (status $status); waiting for the bond" }
            awaitBond()
            status = enableNotifications(descriptor)
        }
        when {
            status == BluetoothGatt.GATT_SUCCESS -> {
                Twig.debug { "Subscribed to Ledger service ${spec.serviceUuid}" }
            }

            status.isAuthenticationFailure() -> {
                throw LedgerException.PairingRefused(
                    reason =
                        "the device refused the subscription on a bonded link; remove it from the phone's " +
                            "Bluetooth settings and pair again"
                )
            }

            else -> {
                throw LedgerException.ConnectionFailed(reason = "subscribing failed with status $status")
            }
        }
    }

    private suspend fun enableNotifications(descriptor: BluetoothGattDescriptor): Int =
        operation(Operation.WRITE_DESCRIPTOR, PAIRING_TIMEOUT) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                it.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ==
                    BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                it.writeDescriptor(descriptor)
            }
        }

    /**
     * Waits for the bond Android started on the refused operation, asking for one if it did not.
     * A pairing that ends unbonded is refused; a `BOND_NONE` read before pairing has started is not
     * taken for an ending.
     *
     * The wait has no deadline of its own: [open]'s connect timeout bounds the whole setup, pairing
     * included, and turns a pairing still running when it runs out into
     * [LedgerException.PairingRefused]. A timeout of the caller's own propagates as a cancellation.
     */
    private suspend fun awaitBond() {
        var pairingStarted = bondState.value == BluetoothDevice.BOND_BONDING
        if (bondState.value == BluetoothDevice.BOND_NONE) {
            pairingStarted = device.createBond()
        }
        val state =
            bondState.first {
                if (it == BluetoothDevice.BOND_BONDING) {
                    pairingStarted = true
                }
                it == BluetoothDevice.BOND_BONDED || (it == BluetoothDevice.BOND_NONE && pairingStarted)
            }
        if (state != BluetoothDevice.BOND_BONDED) {
            throw LedgerException.PairingRefused(reason = "pairing was declined or failed")
        }
    }

    /**
     * Starts one GATT operation and waits for its callback's status. [start] returns whether the stack
     * accepted the call.
     *
     * The pending operation is tagged with its [kind], and a callback completes it only when the kinds
     * match: the callback of an operation that already timed out may still arrive, and must not be
     * taken for the result of the next one.
     *
     * The pending operation is published before `closed` is read, so a [close] racing the start is
     * either seen here, or finds the operation published and fails it.
     */
    private suspend fun operation(
        kind: Operation,
        timeout: Duration,
        start: (BluetoothGatt) -> Boolean
    ): Int =
        operationMutex.withLock {
            val gatt = gatt ?: throw LedgerException.Disconnected()
            val pending = PendingOperation(kind, CompletableDeferred())
            pendingOperation.set(pending)
            try {
                if (closed.get() || !start(gatt)) {
                    throw LedgerException.Disconnected()
                }
                withTimeout(timeout) { pending.result.await() }
            } finally {
                pendingOperation.compareAndSet(pending, null)
            }
        }

    private fun completeOperation(
        kind: Operation,
        status: Int
    ) {
        pendingOperation
            .get()
            ?.takeIf { it.kind == kind }
            ?.result
            ?.complete(status)
    }

    private fun writeCharacteristic(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        frame: ByteArray
    ): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(characteristic, frame, writeType) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            characteristic.writeType = writeType
            @Suppress("DEPRECATION")
            characteristic.value = frame
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(characteristic)
        }

    private enum class Operation {
        MTU,
        DISCOVER_SERVICES,
        WRITE_DESCRIPTOR,
        WRITE_CHARACTERISTIC
    }

    private class PendingOperation(
        val kind: Operation,
        val result: CompletableDeferred<Int>
    )

    companion object {
        /** The ATT MTU LedgerHQ's Android transport requests; the firmware caps it at 156. */
        private const val REQUESTED_ATT_MTU = 156

        /** `GATT_INSUFFICIENT_AUTHENTICATION`, `GATT_INSUFFICIENT_ENCRYPTION`, and the stack's
         * `GATT_AUTH_FAIL` (137). */
        private val AUTHENTICATION_FAILURES = setOf(5, 15, 137)

        private val CLIENT_CHARACTERISTIC_CONFIGURATION: UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private val OPERATION_TIMEOUT = 10.seconds
        private val MTU_TIMEOUT = 5.seconds

        /**
         * How long the subscription's descriptor write may take: on a device the phone has not bonded
         * with, Android's pairing flow runs before it completes.
         */
        private val PAIRING_TIMEOUT = 60.seconds

        private fun Int.isAuthenticationFailure() = this in AUTHENTICATION_FAILURES

        private fun intentDevice(intent: Intent): BluetoothDevice? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            }

        /**
         * Connects to the device at [address] and sets up the Ledger service.
         *
         * An invalid address is [LedgerException.DeviceNotFound] without its exception attached: that
         * exception's message is the address itself.
         *
         * A [SecurityException] from the Bluetooth stack, a permission revoked or never granted
         * despite [usableBluetoothAdapter]'s check, is [LedgerException.BluetoothUnauthorized] naming
         * the permissions to request, as [bluetoothUnauthorized] reads them. When
         * [connectTimeout] runs out while Android's pairing flow is still running, the user did not
         * finish pairing: that is [LedgerException.PairingRefused], not a connection failure. A GATT
         * operation of the setup that runs out of its own, shorter timeout is
         * [LedgerException.ConnectionFailed], whatever the bond state.
         *
         * @throws LedgerException for every failure other than the caller's own cancellation; the link
         *         is closed first.
         */
        @Suppress("TooGenericExceptionCaught", "SwallowedException", "ThrowsCount")
        suspend fun open(
            context: Context,
            address: String,
            connectTimeout: Duration
        ): AndroidGattLink {
            val adapter = usableBluetoothAdapter(context)
            val device =
                try {
                    adapter.getRemoteDevice(address)
                } catch (e: IllegalArgumentException) {
                    throw LedgerException.DeviceNotFound()
                }
            val link =
                try {
                    AndroidGattLink(context.applicationContext, device)
                } catch (e: SecurityException) {
                    throw bluetoothUnauthorized(context, e)
                }
            try {
                withTimeout(connectTimeout) { link.connectAndSetUp() }
                return link
            } catch (e: TimeoutCancellationException) {
                val pairing = link.bondState.value == BluetoothDevice.BOND_BONDING
                link.close()
                currentCoroutineContext().ensureActive()
                throw if (pairing) {
                    LedgerException.PairingRefused(reason = "pairing did not complete in time")
                } else {
                    LedgerException.ConnectionFailed(reason = "connecting timed out", cause = e)
                }
            } catch (e: SecurityException) {
                link.close()
                throw bluetoothUnauthorized(context, e)
            } catch (e: Throwable) {
                link.close()
                throw e
            }
        }
    }
}
