package dev.naz.jogtherobot.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.naz.jogtherobot.bluetooth.Axis
import dev.naz.jogtherobot.bluetooth.BtDevice
import dev.naz.jogtherobot.bluetooth.JogDirection
import dev.naz.jogtherobot.bluetooth.JogProtocol
import dev.naz.jogtherobot.ui.theme.JogTheRobotTheme
import kotlinx.coroutines.launch

/**
 * 1. User selects one of the five axes.
 * 2. User presses Left or Right -> "<AXIS>:<DIR>\n" is sent over Bluetooth.
 */
@Composable
fun JogScreen(
    device: BtDevice,
    onSend: suspend (String) -> Boolean,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var selectedAxis by rememberSaveable { mutableStateOf<Axis?>(null) }
    var lastCommand by remember { mutableStateOf<String?>(null) }
    var lastSendOk by remember { mutableStateOf(true) }

    fun jog(direction: JogDirection) {
        val axis = selectedAxis ?: return
        val command = JogProtocol.build(axis, direction)
        scope.launch {
            lastSendOk = onSend(command)
            lastCommand = command
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Connected to ${device.name}",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onDisconnect) { Text("Disconnect") }
        }

        Text("1. Select an axis", style = MaterialTheme.typography.titleSmall)
        Axis.entries.forEach { axis ->
            val selected = axis == selectedAxis
            if (selected) {
                Button(
                    onClick = { selectedAxis = axis },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                ) { Text(axis.label) }
            } else {
                OutlinedButton(
                    onClick = { selectedAxis = axis },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                ) { Text(axis.label) }
            }
        }

        Spacer(Modifier.height(4.dp))
        Text(
            text = selectedAxis?.let { "2. Move ${it.label}" } ?: "2. Move (select an axis first)",
            style = MaterialTheme.typography.titleSmall
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            JogButton(
                text = JogDirection.LEFT.label,
                enabled = selectedAxis != null,
                leading = true,
                onClick = { jog(JogDirection.LEFT) },
                modifier = Modifier.weight(1f)
            )
            JogButton(
                text = JogDirection.RIGHT.label,
                enabled = selectedAxis != null,
                leading = false,
                onClick = { jog(JogDirection.RIGHT) },
                modifier = Modifier.weight(1f)
            )
        }

        lastCommand?.let { cmd ->
            Text(
                text = (if (lastSendOk) "Sent: " else "Failed to send: ") +
                    cmd.replace("\n", "\\n"),
                fontFamily = FontFamily.Monospace,
                color = if (lastSendOk) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun JogButton(
    text: String,
    enabled: Boolean,
    leading: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(72.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.tertiary,
            contentColor = MaterialTheme.colorScheme.onTertiary
        )
    ) {
        if (leading) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = null,
                modifier = Modifier.size(32.dp)
            )
        }
        Text(text, style = MaterialTheme.typography.titleMedium)
        if (!leading) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(32.dp)
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun JogScreenPreview() {
    JogTheRobotTheme {
        JogScreen(BtDevice("JogTheRobot", "00:11:22:33:44:55"), onSend = { true }, onDisconnect = {})
    }
}
