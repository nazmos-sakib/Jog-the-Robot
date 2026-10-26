package dev.naz.jogtherobot.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.naz.jogtherobot.bluetooth.BtDevice
import dev.naz.jogtherobot.bluetooth.ConnectionState
import dev.naz.jogtherobot.ui.theme.JogTheRobotTheme

@Composable
fun HomeScreen(
    connectionState: ConnectionState,
    bluetoothEnabled: Boolean,
    onShowSavedDevices: () -> Unit,
    onOpenJog: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (connectionState is ConnectionState.Connected)
                    MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                when (connectionState) {
                    is ConnectionState.Connected -> {
                        Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = null,
                            modifier = Modifier.size(56.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(12.dp))
                        Text("Connected", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "${connectionState.device.name}\n${connectionState.device.address}",
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.height(20.dp))
                        Button(onClick = onOpenJog) { Text("Open Jog screen") }
                    }

                    is ConnectionState.Connecting -> {
                        CircularProgressIndicator(modifier = Modifier.size(48.dp))
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "Connecting to ${connectionState.device.name}…",
                            style = MaterialTheme.typography.titleMedium,
                            textAlign = TextAlign.Center
                        )
                    }

                    is ConnectionState.Disconnected -> {
                        Icon(
                            Icons.Filled.Warning,
                            contentDescription = null,
                            modifier = Modifier.size(56.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.height(12.dp))
                        Text("Not connected", style = MaterialTheme.typography.headlineSmall)
                        connectionState.reason?.let {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                it,
                                color = MaterialTheme.colorScheme.error,
                                textAlign = TextAlign.Center
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = if (bluetoothEnabled)
                                "Connect to a Bluetooth device to start jogging the robot."
                            else
                                "Bluetooth is turned off. Turn it on, then connect to a Bluetooth device.",
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.height(20.dp))
                        Button(onClick = onShowSavedDevices) { Text("Connect to a device") }
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun HomeDisconnectedPreview() {
    JogTheRobotTheme {
        HomeScreen(ConnectionState.Disconnected("Connection to JogTheRobot was lost"), true, {}, {})
    }
}

@Preview(showBackground = true)
@Composable
private fun HomeConnectedPreview() {
    JogTheRobotTheme {
        HomeScreen(
            ConnectionState.Connected(BtDevice("JogTheRobot", "00:11:22:33:44:55")), true, {}, {}
        )
    }
}
