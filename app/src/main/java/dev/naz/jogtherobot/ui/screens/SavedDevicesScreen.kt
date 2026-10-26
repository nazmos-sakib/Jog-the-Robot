package dev.naz.jogtherobot.ui.screens

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.naz.jogtherobot.bluetooth.BluetoothController
import dev.naz.jogtherobot.bluetooth.BtDevice
import dev.naz.jogtherobot.bluetooth.ConnectionState

/**
 * Shows devices the app has connected to before ("saved") and nearby BLE
 * devices from a scan. Tapping one connects to it; on success the app
 * navigates to the Jog screen (handled in JogTheRobotApp).
 */
@SuppressLint("MissingPermission") // "Turn on" is only offered once permission is granted
@Composable
fun SavedDevicesScreen(
    controller: BluetoothController,
    connectionState: ConnectionState,
    bluetoothEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    var hasPermission by remember { mutableStateOf(controller.hasPermissions()) }
    var permissionAsked by remember { mutableStateOf(false) }
    val savedDevices by controller.savedDevices.collectAsStateWithLifecycle()
    val scanResults by controller.scanResults.collectAsStateWithLifecycle()
    val isScanning by controller.isScanning.collectAsStateWithLifecycle()

    fun refresh() {
        hasPermission = controller.hasPermissions()
        controller.refreshAdapterState()
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        refresh()
        if (hasPermission) controller.startScan()
    }

    val enableBluetoothLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { refresh() }

    fun requestPermission() = permissionLauncher.launch(controller.requiredPermissions)

    // Ask for the permissions once when the screen opens.
    LaunchedEffect(Unit) {
        if (!hasPermission && !permissionAsked) {
            permissionAsked = true
            requestPermission()
        }
    }
    // Scan while the screen is visible.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        refresh()
        if (hasPermission && controller.bluetoothEnabled.value) controller.startScan()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { controller.stopScan() }
    DisposableEffect(Unit) { onDispose { controller.stopScan() } }
    LaunchedEffect(bluetoothEnabled, hasPermission) {
        refresh()
        if (bluetoothEnabled && hasPermission) controller.startScan()
    }

    // Nearby devices that are not already in the saved list
    val savedAddresses = savedDevices.map { it.address }.toSet()
    val nearby = scanResults.filterNot { it.device.address in savedAddresses }
    val nearbyAddresses = scanResults.map { it.device.address }.toSet()
    val busy = connectionState is ConnectionState.Connecting

    Column(modifier = modifier.fillMaxSize()) {
        when {
            !controller.isBluetoothSupported ->
                MessageBox("This device does not support Bluetooth.")

            !hasPermission ->
                MessageBox(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        "Bluetooth permission is needed to find and connect to your robot."
                    } else {
                        "Location permission is needed to scan for Bluetooth LE devices " +
                            "on this Android version."
                    },
                    buttonText = "Grant permission",
                    onButton = ::requestPermission
                )

            !bluetoothEnabled ->
                MessageBox(
                    "Bluetooth is turned off.",
                    buttonText = "Turn on Bluetooth",
                    onButton = {
                        enableBluetoothLauncher.launch(
                            Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                        )
                    }
                )

            else -> LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            ) {
                if (savedDevices.isNotEmpty()) {
                    item { SectionHeader("Saved devices") }
                    items(savedDevices, key = { "saved-${it.address}" }) { device ->
                        DeviceRow(
                            device = device,
                            subtitle = if (device.address in nearbyAddresses) {
                                "Nearby · ${device.address}"
                            } else {
                                device.address
                            },
                            connectionState = connectionState,
                            enabled = !busy,
                            onClick = { controller.connect(device) },
                            onForget = { controller.forgetDevice(device) },
                        )
                        HorizontalDivider()
                    }
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.weight(1f)) { SectionHeader("Nearby devices") }
                        if (isScanning) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp), strokeWidth = 2.dp
                            )
                            TextButton(onClick = controller::stopScan) { Text("Stop") }
                        } else {
                            TextButton(onClick = controller::startScan) { Text("Scan") }
                        }
                    }
                }

                if (nearby.isEmpty()) {
                    item {
                        Text(
                            text = when {
                                isScanning -> "Searching…"
                                Build.VERSION.SDK_INT < Build.VERSION_CODES.S ->
                                    "No devices found. Make sure the ESP32 is powered on and " +
                                        "Location is turned on in the phone's quick settings."
                                else -> "No devices found. Make sure the ESP32 is powered on."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 12.dp)
                        )
                    }
                } else {
                    items(nearby, key = { "nearby-${it.device.address}" }) { found ->
                        DeviceRow(
                            device = found.device,
                            subtitle = buildString {
                                if (found.isJogController) append("Jog controller · ")
                                append("${found.device.address} · ${found.rssi} dBm")
                            },
                            connectionState = connectionState,
                            enabled = !busy,
                            onClick = { controller.connect(found.device) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
    )
}

@Composable
private fun DeviceRow(
    device: BtDevice,
    subtitle: String,
    connectionState: ConnectionState,
    enabled: Boolean,
    onClick: () -> Unit,
    onForget: (() -> Unit)? = null,
) {
    val isConnecting = connectionState is ConnectionState.Connecting &&
        connectionState.device.address == device.address
    val isConnected = connectionState is ConnectionState.Connected &&
        connectionState.device.address == device.address

    ListItem(
        headlineContent = { Text(device.name) },
        supportingContent = { Text(subtitle) },
        trailingContent = {
            when {
                isConnecting -> CircularProgressIndicator(
                    modifier = Modifier.size(24.dp), strokeWidth = 2.dp
                )
                isConnected -> Text("Connected", color = MaterialTheme.colorScheme.primary)
                onForget != null -> IconButton(onClick = onForget, enabled = enabled) {
                    Icon(Icons.Filled.Delete, contentDescription = "Forget ${device.name}")
                }
            }
        },
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick)
    )
}

@Composable
private fun MessageBox(
    message: String,
    buttonText: String? = null,
    onButton: () -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(message, textAlign = TextAlign.Center)
                if (buttonText != null) {
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onButton) { Text(buttonText) }
                }
            }
        }
    }
}
