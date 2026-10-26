package dev.naz.jogtherobot.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.naz.jogtherobot.bluetooth.BluetoothController
import dev.naz.jogtherobot.bluetooth.BtDevice
import dev.naz.jogtherobot.bluetooth.ConnectionState

/**
 * Lists the devices already paired with the phone. Tapping one connects to it;
 * on success the app navigates to the Jog screen (handled in JogTheRobotApp).
 */
@SuppressLint("MissingPermission") // "Turn on" is only offered once permission is granted
@Composable
fun SavedDevicesScreen(
    controller: BluetoothController,
    connectionState: ConnectionState,
    bluetoothEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var hasPermission by remember { mutableStateOf(controller.hasConnectPermission()) }
    var devices by remember { mutableStateOf(controller.pairedDevices()) }
    var permissionAsked by remember { mutableStateOf(false) }

    fun refresh() {
        hasPermission = controller.hasConnectPermission()
        controller.refreshAdapterState()
        devices = controller.pairedDevices()
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refresh() }

    val enableBluetoothLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { refresh() }

    fun requestPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissionLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT))
        }
    }

    fun openBluetoothSettings() {
        try {
            context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        } catch (_: ActivityNotFoundException) {
            context.startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    // Ask for the permission once when the screen opens.
    LaunchedEffect(Unit) {
        if (!hasPermission && !permissionAsked) {
            permissionAsked = true
            requestPermission()
        }
    }
    // Re-read the list when coming back (e.g. after pairing in system settings).
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh() }
    LaunchedEffect(bluetoothEnabled) { refresh() }

    Column(modifier = modifier.fillMaxSize()) {
        when {
            !controller.isBluetoothSupported ->
                MessageBox("This device does not support Bluetooth.")

            !hasPermission ->
                MessageBox(
                    "Bluetooth permission is needed to show your saved devices.",
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

            else -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 4.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Tap a device to connect",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = ::refresh) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                    }
                }

                if (devices.isEmpty()) {
                    MessageBox(
                        "No saved devices. Pair your robot's Bluetooth module in the system " +
                            "Bluetooth settings first (default PIN for HC-05/HC-06 is usually 1234).",
                        buttonText = "Open Bluetooth settings",
                        onButton = ::openBluetoothSettings
                    )
                } else {
                    val busy = connectionState is ConnectionState.Connecting
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        items(devices, key = { it.address }) { device ->
                            DeviceRow(
                                device = device,
                                connectionState = connectionState,
                                enabled = !busy,
                                onClick = { controller.connect(device) }
                            )
                            HorizontalDivider()
                        }
                    }
                    OutlinedButton(
                        onClick = ::openBluetoothSettings,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) { Text("Pair a new device") }
                }
            }
        }
    }
}

@Composable
private fun DeviceRow(
    device: BtDevice,
    connectionState: ConnectionState,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val isConnecting = connectionState is ConnectionState.Connecting &&
        connectionState.device.address == device.address
    val isConnected = connectionState is ConnectionState.Connected &&
        connectionState.device.address == device.address

    ListItem(
        headlineContent = { Text(device.name) },
        supportingContent = { Text(device.address) },
        trailingContent = {
            when {
                isConnecting -> CircularProgressIndicator(
                    modifier = Modifier.size(24.dp), strokeWidth = 2.dp
                )
                isConnected -> Text("Connected", color = MaterialTheme.colorScheme.primary)
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
