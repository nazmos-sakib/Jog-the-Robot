package dev.naz.jogtherobot.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/** A Bluetooth LE device the app can connect to. */
data class BtDevice(val name: String, val address: String)

/** A device found by a BLE scan. [isJogController] is true when it advertises the UART service. */
data class ScannedDevice(val device: BtDevice, val rssi: Int, val isJogController: Boolean)

sealed interface ConnectionState {
    /** [reason] is shown on the home screen, e.g. after the connection was lost. */
    data class Disconnected(val reason: String? = null) : ConnectionState
    data class Connecting(val device: BtDevice) : ConnectionState
    data class Connected(val device: BtDevice) : ConnectionState
}

/** One-off events the UI reacts to (navigation, snackbars). */
sealed interface BluetoothEvent {
    data class Connected(val device: BtDevice) : BluetoothEvent
    data class ConnectionFailed(val device: BtDevice, val reason: String) : BluetoothEvent
    data class ConnectionLost(val device: BtDevice, val reason: String) : BluetoothEvent
}

/**
 * Nordic UART Service (NUS) UUIDs. They must match the ESP32 sketch
 * (esp32/JogReceiver/JogReceiver.ino).
 */
object BleUuids {
    val UART_SERVICE: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")

    /** The app writes commands here. */
    val UART_RX: UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")

    /** The device sends replies here as notifications. */
    val UART_TX: UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")

    /** Client Characteristic Configuration descriptor, used to turn notifications on. */
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")
}

/**
 * Bluetooth Low Energy connection manager for the ESP32-S3 (which has no
 * Classic Bluetooth). It scans for devices, connects over GATT and writes
 * commands to the UART service's RX characteristic.
 *
 * A broken link is detected by the GATT callback, and also by a
 * [BroadcastReceiver] that listens for ACL disconnects and Bluetooth being
 * turned off.
 */
@SuppressLint("MissingPermission") // every entry point checks hasPermissions()
class BluetoothController(context: Context) {

    companion object {
        private const val TAG = "BluetoothController"
        private const val PREFS_NAME = "saved_devices"
        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val WRITE_TIMEOUT_MS = 2_000L
        private const val SCAN_DURATION_MS = 15_000L
        private const val REQUESTED_MTU = 185
        private const val DEFAULT_MTU = 23
    }

    private val appContext = context.applicationContext
    private val adapter: BluetoothAdapter? =
        appContext.getSystemService(BluetoothManager::class.java)?.adapter
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val writeMutex = Mutex()

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected())
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _bluetoothEnabled = MutableStateFlow(isAdapterEnabled())
    val bluetoothEnabled: StateFlow<Boolean> = _bluetoothEnabled.asStateFlow()

    private val _events = MutableSharedFlow<BluetoothEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<BluetoothEvent> = _events.asSharedFlow()

    /** Raw bytes received from the device (for later use). */
    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    val incoming: SharedFlow<ByteArray> = _incoming.asSharedFlow()

    /** Devices the app has connected to before, sorted by name. */
    private val _savedDevices = MutableStateFlow(loadSavedDevices())
    val savedDevices: StateFlow<List<BtDevice>> = _savedDevices.asStateFlow()

    /** Results of the current/last scan; jog controllers first. */
    private val _scanResults = MutableStateFlow<List<ScannedDevice>>(emptyList())
    val scanResults: StateFlow<List<ScannedDevice>> = _scanResults.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var rxChar: BluetoothGattCharacteristic? = null
    @Volatile private var mtu = DEFAULT_MTU
    @Volatile private var discoveryStarted = false
    @Volatile private var pendingWrite: CompletableDeferred<Boolean>? = null
    private var connectTimeoutJob: Job? = null
    private var scanStopJob: Job? = null

    val isBluetoothSupported: Boolean get() = adapter != null

    // ------------------------------------------------------------------
    // Broadcast receiver: detects a broken connection
    // ------------------------------------------------------------------

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    val device = IntentCompat.getParcelableExtra(
                        intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java
                    )
                    val current = (_state.value as? ConnectionState.Connected)?.device ?: return
                    if (device?.address == current.address) {
                        Log.d(TAG, "ACL disconnected from ${current.address}")
                        handleConnectionLost("Connection to ${current.name} was lost")
                    }
                }

                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val btState = intent.getIntExtra(
                        BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR
                    )
                    _bluetoothEnabled.value = btState == BluetoothAdapter.STATE_ON
                    if (btState == BluetoothAdapter.STATE_TURNING_OFF ||
                        btState == BluetoothAdapter.STATE_OFF
                    ) {
                        _isScanning.value = false
                        scanStopJob?.cancel()
                        handleConnectionLost("Bluetooth was turned off")
                        (_state.value as? ConnectionState.Connecting)?.let {
                            failConnecting(it.device, "Bluetooth was turned off")
                        }
                    }
                }
            }
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        // Registered on the application context: lives as long as the app process.
        ContextCompat.registerReceiver(
            appContext, receiver, filter, ContextCompat.RECEIVER_EXPORTED
        )
    }

    // ------------------------------------------------------------------
    // Permissions / adapter
    // ------------------------------------------------------------------

    /**
     * Runtime permissions needed to scan for and connect to BLE devices.
     * Android 12+ has dedicated Bluetooth permissions; older versions need
     * location permission to see scan results.
     */
    val requiredPermissions: Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    fun hasPermissions(): Boolean = requiredPermissions.all {
        ContextCompat.checkSelfPermission(appContext, it) == PackageManager.PERMISSION_GRANTED
    }

    fun refreshAdapterState() {
        _bluetoothEnabled.value = isAdapterEnabled()
    }

    private fun isAdapterEnabled(): Boolean =
        try {
            adapter?.isEnabled == true
        } catch (_: SecurityException) {
            false
        }

    // ------------------------------------------------------------------
    // Saved devices
    // ------------------------------------------------------------------

    private fun loadSavedDevices(): List<BtDevice> =
        prefs.all
            .mapNotNull { (address, name) -> (name as? String)?.let { BtDevice(it, address) } }
            .sortedBy { it.name.lowercase() }

    private fun saveDevice(device: BtDevice) {
        prefs.edit().putString(device.address, device.name).apply()
        _savedDevices.value = loadSavedDevices()
    }

    fun forgetDevice(device: BtDevice) {
        prefs.edit().remove(device.address).apply()
        _savedDevices.value = loadSavedDevices()
    }

    // ------------------------------------------------------------------
    // Scanning
    // ------------------------------------------------------------------

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = addScanResult(result)

        override fun onBatchScanResults(results: MutableList<ScanResult>) =
            results.forEach(::addScanResult)

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "Scan failed with error $errorCode")
            _isScanning.value = false
        }
    }

    private fun addScanResult(result: ScanResult) {
        val name = result.scanRecord?.deviceName
            ?: try {
                result.device.name
            } catch (_: SecurityException) {
                null
            }
            ?: return // unnamed devices are just noise in the list
        val address = result.device.address
        val advertisesUart =
            result.scanRecord?.serviceUuids?.any { it.uuid == BleUuids.UART_SERVICE } == true

        _scanResults.update { list ->
            val previous = list.find { it.device.address == address }
            val found = ScannedDevice(
                device = BtDevice(name, address),
                rssi = result.rssi,
                isJogController = advertisesUart || previous?.isJogController == true,
            )
            (list.filterNot { it.device.address == address } + found).sortedWith(
                compareByDescending<ScannedDevice> { it.isJogController }
                    .thenBy { it.device.name.lowercase() }
            )
        }
    }

    fun startScan() {
        if (_isScanning.value || !hasPermissions() || !isAdapterEnabled()) return
        val scanner = adapter?.bluetoothLeScanner ?: return
        _scanResults.value = emptyList()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        try {
            scanner.startScan(null, settings, scanCallback)
        } catch (e: Exception) {
            Log.w(TAG, "Could not start scan", e)
            return
        }
        _isScanning.value = true
        scanStopJob?.cancel()
        scanStopJob = scope.launch {
            delay(SCAN_DURATION_MS)
            stopScan()
        }
    }

    fun stopScan() {
        if (!_isScanning.value) return
        _isScanning.value = false
        scanStopJob?.cancel()
        try {
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (e: Exception) {
            Log.w(TAG, "Could not stop scan", e)
        }
    }

    // ------------------------------------------------------------------
    // Connect / disconnect
    // ------------------------------------------------------------------

    fun connect(device: BtDevice) {
        val btAdapter = adapter
        when {
            btAdapter == null -> return fail(device, "Bluetooth is not supported on this device")
            !hasPermissions() -> return fail(device, "Bluetooth permission not granted")
            !isAdapterEnabled() -> return fail(device, "Bluetooth is turned off")
        }

        synchronized(this) {
            when (val current = _state.value) {
                is ConnectionState.Connecting -> return // already busy
                is ConnectionState.Connected -> if (current.device.address == device.address) {
                    _events.tryEmit(BluetoothEvent.Connected(current.device))
                    return
                }
                is ConnectionState.Disconnected -> Unit
            }
            closeGattLocked()
            _state.value = ConnectionState.Connecting(device)
        }

        // Scanning at the same time slows the connection down.
        stopScan()

        val remote = try {
            btAdapter!!.getRemoteDevice(device.address)
        } catch (e: IllegalArgumentException) {
            return failConnecting(device, "Invalid device address")
        }
        val newGatt = remote.connectGatt(
            appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE
        ) ?: return failConnecting(device, "Could not start the connection")

        synchronized(this) {
            if (_state.value != ConnectionState.Connecting(device)) {
                newGatt.close() // user cancelled meanwhile
                return
            }
            gatt = newGatt
        }

        connectTimeoutJob?.cancel()
        connectTimeoutJob = scope.launch {
            delay(CONNECT_TIMEOUT_MS)
            failConnecting(device, "Timed out")
        }
    }

    /** User-initiated disconnect. Does not emit [BluetoothEvent.ConnectionLost]. */
    fun disconnect() {
        connectTimeoutJob?.cancel()
        synchronized(this) {
            closeGattLocked()
            _state.value = ConnectionState.Disconnected()
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (!isCurrent(g)) {
                g.close()
                return
            }
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                if (_state.value is ConnectionState.Connecting) {
                    // Ask for a bigger MTU first; services are discovered in onMtuChanged.
                    if (!g.requestMtu(REQUESTED_MTU)) startDiscovery(g)
                }
                return
            }
            // Disconnected, or a connection error (e.g. status 133).
            Log.d(TAG, "GATT disconnected, status=$status newState=$newState")
            when (val current = _state.value) {
                is ConnectionState.Connecting -> failConnecting(current.device, "GATT error $status")
                is ConnectionState.Connected ->
                    handleConnectionLost("Connection to ${current.device.name} was lost")
                is ConnectionState.Disconnected -> Unit
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
            if (!isCurrent(g)) return
            if (status == BluetoothGatt.GATT_SUCCESS) mtu = newMtu
            if (_state.value is ConnectionState.Connecting) startDiscovery(g)
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (!isCurrent(g)) return
            val current = _state.value as? ConnectionState.Connecting ?: return
            val service = g.getService(BleUuids.UART_SERVICE)
            val rx = service?.getCharacteristic(BleUuids.UART_RX)
            if (status != BluetoothGatt.GATT_SUCCESS || rx == null) {
                return failConnecting(current.device, "Device has no Jog (UART) service")
            }
            rxChar = rx
            val tx = service.getCharacteristic(BleUuids.UART_TX)
            // With notifications on, finishConnecting() runs in onDescriptorWrite.
            if (tx == null || !enableNotifications(g, tx)) finishConnecting(g, current.device)
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            if (!isCurrent(g) || descriptor.uuid != BleUuids.CCCD) return
            val current = _state.value as? ConnectionState.Connecting ?: return
            finishConnecting(g, current.device)
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (isCurrent(g)) pendingWrite?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        // Android 13+
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (isCurrent(g) && characteristic.uuid == BleUuids.UART_TX) {
                _incoming.tryEmit(value.copyOf())
            }
        }

        // Android 12 and lower
        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            val value = characteristic.value ?: return
            if (isCurrent(g) && characteristic.uuid == BleUuids.UART_TX) {
                _incoming.tryEmit(value.copyOf())
            }
        }
    }

    /**
     * True if [g] belongs to the connection in progress. Callbacks can arrive
     * before connectGatt() has returned, so a matching device is adopted then.
     */
    private fun isCurrent(g: BluetoothGatt): Boolean = synchronized(this) {
        if (gatt === g) return true
        val connecting = _state.value as? ConnectionState.Connecting
        if (gatt == null && connecting != null && connecting.device.address == g.device.address) {
            gatt = g
            return true
        }
        false
    }

    private fun startDiscovery(g: BluetoothGatt) {
        if (discoveryStarted) return
        discoveryStarted = true
        if (!g.discoverServices()) {
            (_state.value as? ConnectionState.Connecting)?.let {
                failConnecting(it.device, "Service discovery failed")
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun enableNotifications(g: BluetoothGatt, tx: BluetoothGattCharacteristic): Boolean {
        if (!g.setCharacteristicNotification(tx, true)) return false
        val cccd = tx.getDescriptor(BleUuids.CCCD) ?: return false
        val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(cccd, value) == BluetoothStatusCodes.SUCCESS
        } else {
            cccd.value = value
            g.writeDescriptor(cccd)
        }
    }

    private fun finishConnecting(g: BluetoothGatt, device: BtDevice) {
        val accepted = synchronized(this) {
            if (gatt === g && _state.value == ConnectionState.Connecting(device)) {
                _state.value = ConnectionState.Connected(device)
                true
            } else {
                false
            }
        }
        if (!accepted) return
        connectTimeoutJob?.cancel()
        Log.d(TAG, "Connected to ${device.address}, mtu=$mtu")
        saveDevice(device)
        _events.tryEmit(BluetoothEvent.Connected(device))
    }

    private fun failConnecting(device: BtDevice, reason: String) {
        synchronized(this) {
            if (_state.value != ConnectionState.Connecting(device)) return
            closeGattLocked()
            _state.value = ConnectionState.Disconnected("Could not connect to ${device.name}")
        }
        connectTimeoutJob?.cancel()
        Log.w(TAG, "Connection to ${device.address} failed: $reason")
        _events.tryEmit(BluetoothEvent.ConnectionFailed(device, reason))
    }

    // ------------------------------------------------------------------
    // Sending
    // ------------------------------------------------------------------

    suspend fun send(text: String): Boolean = send(text.toByteArray(Charsets.UTF_8))

    /** Writes [data] to the UART RX characteristic, split into MTU-sized packets. */
    suspend fun send(data: ByteArray): Boolean {
        writeMutex.withLock {
            val g = gatt
            val rx = rxChar
            if (g == null || rx == null || _state.value !is ConnectionState.Connected) return false
            val chunkSize = (mtu - 3).coerceAtLeast(20)
            var offset = 0
            while (offset < data.size) {
                val chunk = data.copyOfRange(offset, minOf(data.size, offset + chunkSize))
                if (!writeChunk(g, rx, chunk)) {
                    Log.w(TAG, "Write failed")
                    return false
                }
                offset += chunk.size
            }
            return true
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun writeChunk(
        g: BluetoothGatt,
        rx: BluetoothGattCharacteristic,
        chunk: ByteArray,
    ): Boolean {
        val done = CompletableDeferred<Boolean>()
        pendingWrite = done
        val writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT // with response
        val started = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(rx, chunk, writeType) == BluetoothStatusCodes.SUCCESS
            } else {
                rx.writeType = writeType
                rx.value = chunk
                g.writeCharacteristic(rx)
            }
        } catch (e: SecurityException) {
            false
        }
        val ok = started && (withTimeoutOrNull(WRITE_TIMEOUT_MS) { done.await() } ?: false)
        pendingWrite = null
        return ok
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun handleConnectionLost(reason: String) {
        val device = synchronized(this) {
            val current = _state.value as? ConnectionState.Connected ?: return
            closeGattLocked()
            _state.value = ConnectionState.Disconnected(reason)
            current.device
        }
        _events.tryEmit(BluetoothEvent.ConnectionLost(device, reason))
    }

    private fun fail(device: BtDevice, reason: String) {
        _events.tryEmit(BluetoothEvent.ConnectionFailed(device, reason))
    }

    /** Must be called while holding the lock on `this`. */
    private fun closeGattLocked() {
        val g = gatt
        gatt = null // set first so late callbacks from this connection are ignored
        rxChar = null
        mtu = DEFAULT_MTU
        discoveryStarted = false
        pendingWrite?.complete(false)
        try {
            g?.disconnect()
            g?.close()
        } catch (e: SecurityException) {
            Log.w(TAG, "Could not close GATT", e)
        }
    }
}
