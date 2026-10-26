package dev.naz.jogtherobot.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.IOException
import java.util.UUID

/** A paired ("saved") Bluetooth device. */
data class BtDevice(val name: String, val address: String)

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
 * Classic Bluetooth (SPP / RFCOMM) connection manager — the profile used by
 * HC-05 / HC-06 style modules that show up as `BTSerial` on the microcontroller.
 *
 * A [BroadcastReceiver] listens for ACL disconnects and Bluetooth being turned
 * off, so a broken link is detected even when no data is being sent. The read
 * loop on the socket acts as a second line of detection.
 */
@SuppressLint("MissingPermission") // every entry point checks hasConnectPermission()
class BluetoothController(context: Context) {

    companion object {
        private const val TAG = "BluetoothController"

        /** Standard Serial Port Profile UUID. */
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }

    private val appContext = context.applicationContext
    private val adapter: BluetoothAdapter? =
        appContext.getSystemService(BluetoothManager::class.java)?.adapter

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
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

    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var pendingSocket: BluetoothSocket? = null
    private var connectJob: Job? = null

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
                        handleConnectionLost("Bluetooth was turned off")
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

    fun hasConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(
                appContext, Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED

    fun refreshAdapterState() {
        _bluetoothEnabled.value = isAdapterEnabled()
    }

    private fun isAdapterEnabled(): Boolean =
        try {
            adapter?.isEnabled == true
        } catch (_: SecurityException) {
            false
        }

    /** Devices already paired with the phone, sorted by name. */
    fun pairedDevices(): List<BtDevice> {
        if (!hasConnectPermission()) return emptyList()
        return try {
            adapter?.bondedDevices.orEmpty()
                .map { BtDevice(name = it.name ?: "Unknown device", address = it.address) }
                .sortedBy { it.name.lowercase() }
        } catch (e: SecurityException) {
            Log.w(TAG, "No permission to read bonded devices", e)
            emptyList()
        }
    }

    // ------------------------------------------------------------------
    // Connect / disconnect
    // ------------------------------------------------------------------

    fun connect(device: BtDevice) {
        val btAdapter = adapter
        when {
            btAdapter == null -> return fail(device, "Bluetooth is not supported on this device")
            !hasConnectPermission() -> return fail(device, "Bluetooth permission not granted")
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
            closeSocketLocked()
            _state.value = ConnectionState.Connecting(device)
        }

        connectJob = scope.launch {
            var newSocket: BluetoothSocket? = null
            try {
                val remote = btAdapter!!.getRemoteDevice(device.address)
                newSocket = openSocket(remote)

                val accepted = synchronized(this@BluetoothController) {
                    if (_state.value == ConnectionState.Connecting(device)) {
                        socket = newSocket
                        _state.value = ConnectionState.Connected(device)
                        true
                    } else {
                        false // user cancelled while we were connecting
                    }
                }
                if (!accepted) {
                    newSocket.closeQuietly()
                    return@launch
                }

                Log.d(TAG, "Connected to ${device.address}")
                _events.emit(BluetoothEvent.Connected(device))
                readLoop(newSocket, device)
            } catch (e: CancellationException) {
                newSocket?.closeQuietly()
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Connection to ${device.address} failed", e)
                newSocket?.closeQuietly()
                val stillPending = synchronized(this@BluetoothController) {
                    if (_state.value == ConnectionState.Connecting(device)) {
                        _state.value =
                            ConnectionState.Disconnected("Could not connect to ${device.name}")
                        true
                    } else {
                        false
                    }
                }
                if (stillPending) {
                    _events.emit(
                        BluetoothEvent.ConnectionFailed(device, e.message ?: e.javaClass.simpleName)
                    )
                }
            }
        }
    }

    /** User-initiated disconnect. Does not emit [BluetoothEvent.ConnectionLost]. */
    fun disconnect() {
        connectJob?.cancel()
        synchronized(this) {
            pendingSocket?.closeQuietly()
            pendingSocket = null
            closeSocketLocked()
            _state.value = ConnectionState.Disconnected()
        }
    }

    private fun openSocket(remote: BluetoothDevice): BluetoothSocket {
        val primary = remote.createRfcommSocketToServiceRecord(SPP_UUID)
        pendingSocket = primary
        try {
            primary.connect()
            return primary
        } catch (e: IOException) {
            primary.closeQuietly()
            if (_state.value !is ConnectionState.Connecting) throw e

            // Fallback for modules whose SDP lookup fails (seen on some HC-05/HC-06).
            val fallback = try {
                remote.javaClass
                    .getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                    .invoke(remote, 1) as BluetoothSocket
            } catch (_: Exception) {
                throw e
            }
            pendingSocket = fallback
            try {
                fallback.connect()
                return fallback
            } catch (_: IOException) {
                fallback.closeQuietly()
                throw e
            }
        } finally {
            pendingSocket = null
        }
    }

    private suspend fun readLoop(s: BluetoothSocket, device: BtDevice) {
        val buffer = ByteArray(1024)
        try {
            val input = s.inputStream
            while (currentCoroutineContext().isActive) {
                val count = input.read(buffer) // blocks until data or disconnect
                if (count < 0) throw IOException("Stream closed")
                if (count > 0) _incoming.emit(buffer.copyOf(count))
            }
        } catch (e: IOException) {
            if (socket === s) handleConnectionLost("Connection to ${device.name} was lost")
        }
    }

    // ------------------------------------------------------------------
    // Sending
    // ------------------------------------------------------------------

    suspend fun send(text: String): Boolean = send(text.toByteArray(Charsets.UTF_8))

    suspend fun send(data: ByteArray): Boolean = withContext(Dispatchers.IO) {
        val s = socket ?: return@withContext false
        try {
            writeMutex.withLock {
                s.outputStream.write(data)
                s.outputStream.flush()
            }
            true
        } catch (e: IOException) {
            Log.w(TAG, "Write failed", e)
            if (socket === s) {
                val name = (_state.value as? ConnectionState.Connected)?.device?.name ?: "device"
                handleConnectionLost("Connection to $name was lost")
            }
            false
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun handleConnectionLost(reason: String) {
        val device = synchronized(this) {
            val current = _state.value as? ConnectionState.Connected ?: return
            closeSocketLocked()
            _state.value = ConnectionState.Disconnected(reason)
            current.device
        }
        _events.tryEmit(BluetoothEvent.ConnectionLost(device, reason))
    }

    private fun fail(device: BtDevice, reason: String) {
        _events.tryEmit(BluetoothEvent.ConnectionFailed(device, reason))
    }

    /** Must be called while holding the lock on `this`. */
    private fun closeSocketLocked() {
        val s = socket
        socket = null // set first so the read loop knows this close was intentional
        s?.closeQuietly()
    }

    private fun Closeable.closeQuietly() {
        try {
            close()
        } catch (_: IOException) {
        }
    }
}
