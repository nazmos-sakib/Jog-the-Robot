package dev.naz.jogtherobot.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.Card
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.naz.jogtherobot.bluetooth.Axis
import dev.naz.jogtherobot.bluetooth.BtDevice
import dev.naz.jogtherobot.bluetooth.JogDirection
import dev.naz.jogtherobot.bluetooth.JogProtocol
import dev.naz.jogtherobot.ui.theme.JogTheRobotTheme
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 1. User selects one of the five axes (button or by tapping the joint in the drawing).
 * 2. User presses Left or Right -> "<AXIS>:<DIR>\n" is sent over Bluetooth and,
 *    if it was sent, the joint in the drawing turns by [RobotModel.STEP_DEG].
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
    var angles by rememberSaveable { mutableStateOf(RobotModel.HOME_DEG) }
    var lastCommand by remember { mutableStateOf<String?>(null) }
    var lastSendOk by remember { mutableStateOf(true) }

    fun jog(direction: JogDirection) {
        val axis = selectedAxis ?: return
        val command = JogProtocol.build(axis, direction)
        scope.launch {
            lastSendOk = onSend(command)
            lastCommand = command
            if (lastSendOk) {
                val delta = if (direction == JogDirection.RIGHT) RobotModel.STEP_DEG
                else -RobotModel.STEP_DEG
                angles = RobotModel.jog(angles, axis, delta)
            }
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

        Card(modifier = Modifier.fillMaxWidth()) {
            Box {
                RobotArmView(
                    angles = angles,
                    selectedAxis = selectedAxis,
                    onAxisTap = { selectedAxis = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp)
                )
                Column(Modifier.padding(12.dp)) {
                    Text(
                        text = selectedAxis?.let { "${it.code} · ${it.joint}" } ?: "Tap a joint",
                        style = MaterialTheme.typography.titleMedium
                    )
                    selectedAxis?.let {
                        Text(
                            text = "${angles[it.ordinal].roundToInt()}°",
                            style = MaterialTheme.typography.headlineSmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
                TextButton(
                    onClick = { angles = RobotModel.HOME_DEG },
                    modifier = Modifier.align(Alignment.TopEnd)
                ) { Text("Reset view") }
            }
        }

        Text("1. Select an axis", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Axis.entries.forEach { axis ->
                AxisButton(
                    axis = axis,
                    selected = axis == selectedAxis,
                    onClick = { selectedAxis = axis },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        Text(
            text = selectedAxis?.let { "2. Move ${it.label} (${it.joint})" }
                ?: "2. Move (select an axis first)",
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
        Text(
            text = "The drawing shows the commands sent from this app, not the robot's " +
                "measured position.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun AxisButton(
    axis: Axis,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val padding = PaddingValues(horizontal = 2.dp, vertical = 6.dp)
    val content: @Composable () -> Unit = {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(axis.code, style = MaterialTheme.typography.titleMedium)
            Text(
                axis.joint,
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
                maxLines = 1
            )
        }
    }
    if (selected) {
        Button(
            onClick = onClick,
            contentPadding = padding,
            modifier = modifier.height(60.dp)
        ) { content() }
    } else {
        OutlinedButton(
            onClick = onClick,
            contentPadding = padding,
            modifier = modifier.height(60.dp)
        ) { content() }
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
