package dev.naz.jogtherobot.bluetooth

/** The five robot axes shown on the Jog screen. Change labels/codes here. */
enum class Axis(val label: String, val code: String) {
    AXIS_1("Axis 1", "A1"),
    AXIS_2("Axis 2", "A2"),
    AXIS_3("Axis 3", "A3"),
    AXIS_4("Axis 4", "A4"),
    AXIS_5("Axis 5", "A5"),
}

enum class JogDirection(val label: String, val code: String) {
    LEFT("Left", "L"),
    RIGHT("Right", "R"),
}

/**
 * Builds the text that is sent over Bluetooth.
 *
 * Format: `<AXIS>:<DIR>\n`  e.g. `A1:L\n`, `A3:R\n`
 *
 * The trailing newline lets the microcontroller read one command at a time,
 * e.g. on Arduino: `String cmd = BTSerial.readStringUntil('\n');`
 */
object JogProtocol {
    fun build(axis: Axis, direction: JogDirection): String =
        "${axis.code}:${direction.code}\n"
}
