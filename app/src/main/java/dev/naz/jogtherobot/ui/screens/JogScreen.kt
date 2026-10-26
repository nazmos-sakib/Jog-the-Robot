package dev.naz.jogtherobot.ui.screens

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
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
import dev.naz.jogtherobot.bluetooth.RobotState
import dev.naz.jogtherobot.bluetooth.RobotStatus
import dev.naz.jogtherobot.ui.theme.JogTheRobotTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/**
 * How far the drawing turns per repeated command. The PLC jogs at about
 * 10 deg/s and the command repeats 10 times a second, so 1 deg per repeat.
 * The drawing is only an estimate, not the robot's measured position.
 */
private const val DRAWING_DEG_PER_REPEAT = 1f

/** The ESP32 sends the robot status every second; older than this = no status. */
private const val STATUS_TIMEOUT_MS = 3_000L

/**
 * 1. User selects one of the five axes (button or by tapping the joint in the drawing).
 * 2. User HOLDS Left or Right -> "<AXIS>:<DIR>\n" is sent over Bluetooth every
 *    [JogProtocol.REPEAT_MS] while the finger is down (hold-to-run), and the
 *    joint in the drawing turns a little with each command that was sent.
 * 3. User lifts the finger -> "S\n" is sent and the robot stops.
 *
 * The Robot card shows the PLC state that the ESP32 reports ("ST:<state>,<plc>"),
 * has a Power switch ("P1"/"P0") and a Home button ("H"). Jogging is only
 * possible while the robot is Ready (powered and homed).
 */
@Composable
fun JogScreen(
    device: BtDevice,
    onSend: suspend (String) -> Boolean,
    incoming: Flow<ByteArray>,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var selectedAxis by rememberSaveable { mutableStateOf<Axis?>(null) }
    var angles by rememberSaveable { mutableStateOf(RobotModel.HOME_DEG) }
    var lastCommand by remember { mutableStateOf<String?>(null) }
    var lastSendOk by remember { mutableStateOf(true) }

    // Robot status from the ESP32. null = nothing received for STATUS_TIMEOUT_MS.
    var status by remember { mutableStateOf<RobotStatus?>(null) }
    var lastStatusAt by remember { mutableStateOf(0L) }
    LaunchedEffect(incoming) {
        // BLE notifications can split or join lines, so collect bytes until '\n'.
        val buffer = StringBuilder()
        incoming.collect { bytes ->
            buffer.append(String(bytes, Charsets.UTF_8))
            while (true) {
                val end = buffer.indexOf("\n")
                if (end < 0) break
                val line = buffer.substring(0, end).trim()
                buffer.delete(0, end + 1)
                RobotStatus.parse(line)?.let {
                    status = it
                    lastStatusAt = System.currentTimeMillis()
                }
            }
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            if (System.currentTimeMillis() - lastStatusAt > STATUS_TIMEOUT_MS) status = null
        }
    }
    val canJog = status?.plcOnline == true && status?.state?.canJog == true

    fun sendCommand(command: String) {
        scope.launch {
            lastSendOk = onSend(command)
            lastCommand = command
        }
    }

    // True while a jog button is held. The send loop below checks it.
    val jogging = remember { AtomicBoolean(false) }

    fun startJog(direction: JogDirection) {
        val axis = selectedAxis ?: return
        if (jogging.getAndSet(true)) return // already jogging (other button held)
        val command = JogProtocol.build(axis, direction)
        val delta = if (direction == JogDirection.RIGHT) DRAWING_DEG_PER_REPEAT
        else -DRAWING_DEG_PER_REPEAT
        scope.launch {
            // Keep-alive loop: re-send the command while the button is held.
            // All writes happen one after another in this coroutine, so the
            // stop command never collides with a jog command still in flight.
            while (jogging.get()) {
                lastSendOk = onSend(command)
                lastCommand = command
                if (lastSendOk) angles = RobotModel.jog(angles, axis, delta)
                // Wait REPEAT_MS, but leave early when the button is released.
                val nextSend = System.currentTimeMillis() + JogProtocol.REPEAT_MS
                while (jogging.get() && System.currentTimeMillis() < nextSend) delay(10)
            }
            lastSendOk = onSend(JogProtocol.STOP)
            lastCommand = JogProtocol.STOP
        }
    }

    fun stopJog() {
        jogging.set(false) // the loop above sends STOP and ends
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

        RobotControlCard(
            status = status,
            onPower = { on -> sendCommand(if (on) JogProtocol.POWER_ON else JogProtocol.POWER_OFF) },
            onHome = { sendCommand(JogProtocol.HOME) },
            onStop = { sendCommand(JogProtocol.STOP_PROGRAM) },
        )

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
                enabled = selectedAxis != null && canJog,
                leading = true,
                onPress = { startJog(JogDirection.LEFT) },
                onRelease = { stopJog() },
                modifier = Modifier.weight(1f)
            )
            JogButton(
                text = JogDirection.RIGHT.label,
                enabled = selectedAxis != null && canJog,
                leading = false,
                onPress = { startJog(JogDirection.RIGHT) },
                onRelease = { stopJog() },
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
    onPress: () -> Unit,
    onRelease: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The button tells us when it is pressed and released through its
    // interaction source; a normal onClick only fires after release.
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val currentOnPress by rememberUpdatedState(onPress)
    val currentOnRelease by rememberUpdatedState(onRelease)
    var wasPressed by remember { mutableStateOf(false) }
    LaunchedEffect(pressed) {
        if (pressed) {
            wasPressed = true
            currentOnPress()
        } else if (wasPressed) {
            wasPressed = false
            currentOnRelease()
        }
    }
    Button(
        onClick = {}, // movement is driven by press/release, not by click
        interactionSource = interaction,
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

/**
 * Power switch, Home button and the robot's state. Commands are only offered
 * when the PLC is online and in a state where it accepts them.
 */
@Composable
private fun RobotControlCard(
    status: RobotStatus?,
    onPower: (Boolean) -> Unit,
    onHome: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = status?.state
    val online = status?.plcOnline == true
    val stateText = when {
        status == null -> "No status from the ESP32"
        !online -> "PLC not connected to the ESP32"
        state == null -> "Unknown PLC state ${status.stateCode}"
        else -> state.label
    }
    val hint = when {
        !online -> null
        state == RobotState.DISABLED -> "Switch the power on, then press Home."
        state == RobotState.WAIT_FOR_HOME -> "Press Home before jogging."
        state == RobotState.ERROR -> "Clear the error on the panel, then try again."
        state?.isProgramRunning == true ->
            "A program is running. Press Stop to stop it and take control."
        else -> null
    }
    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Robot", style = MaterialTheme.typography.labelMedium)
                    Text(
                        stateText,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (state == RobotState.ERROR || (status != null && !online))
                            MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurface
                    )
                }
                Text("Power", style = MaterialTheme.typography.labelLarge)
                Switch(
                    checked = state?.isPowered == true,
                    onCheckedChange = onPower,
                    // Power off is never allowed while the robot moves (the PLC checks this too)
                    enabled = online && state != null && !state.isMoving,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = hint ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = onStop,
                    enabled = online && state?.canStop == true,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    ),
                    modifier = Modifier.padding(end = 8.dp)
                ) { Text("Stop") }
                Button(
                    onClick = onHome,
                    enabled = online && state?.canHome == true
                ) { Text("Home") }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun JogScreenPreview() {
    JogTheRobotTheme {
        JogScreen(BtDevice("JogTheRobot", "00:11:22:33:44:55"), onSend = { true }, incoming = emptyFlow(), onDisconnect = {})
    }
}
