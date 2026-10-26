package dev.naz.jogtherobot.bluetooth

/** The five robot axes shown on the Jog screen. Change labels/codes here. */
enum class Axis(val label: String, val code: String, val joint: String) {
    AXIS_1("Axis 1", "A1", "Base"),
    AXIS_2("Axis 2", "A2", "Shoulder"),
    AXIS_3("Axis 3", "A3", "Elbow"),
    AXIS_4("Axis 4", "A4", "Wrist"),
    AXIS_5("Axis 5", "A5", "Tool roll"),
}

enum class JogDirection(val label: String, val code: String) {
    LEFT("Left", "L"),
    RIGHT("Right", "R"),
}

/**
 * Builds the text that is sent over Bluetooth.
 *
 * Format: `<AXIS>:<DIR>\n`  e.g. `A1:L\n`, `A3:R\n`, and `S\n` to stop.
 *
 * Hold-to-run: while a jog button is held, the app re-sends the same jog
 * command every [REPEAT_MS]. The ESP32 treats each command as valid for only
 * 300 ms, so if the commands stop arriving (app crash, phone out of range)
 * the robot stops by itself. [STOP] is sent when the button is released.
 *
 * The trailing newline lets the microcontroller read one command at a time.
 */
object JogProtocol {
    /** While a jog button is held, the command is re-sent this often (keep-alive). */
    const val REPEAT_MS = 100L

    /** Sent when the jog button is released. */
    const val STOP = "S\n"

    fun build(axis: Axis, direction: JogDirection): String =
        "${axis.code}:${direction.code}\n"
}
