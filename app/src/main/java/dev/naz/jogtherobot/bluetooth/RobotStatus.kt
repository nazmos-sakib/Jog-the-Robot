package dev.naz.jogtherobot.bluetooth

/**
 * The states of the PLC's state machine (StateMachine_enum in the Chuka
 * Project, task Main). The numbers must match the PLC.
 */
enum class RobotState(val code: Int, val label: String) {
    DISABLED(0, "Power off"),
    POWER_ON(5, "Powering on…"),
    WAIT_FOR_HOME(9, "Powered, not homed"),
    HOMING(10, "Homing…"),
    IDLE(20, "Ready"),
    GCODE(30, "Running a program"),
    JOGGING(40, "Jogging"),
    MOVE_SET_POS(50, "Moving to a position"),
    ERROR(99, "Error");

    val isPowered: Boolean get() = this != DISABLED
    val canHome: Boolean get() = this == WAIT_FOR_HOME || this == IDLE
    val canJog: Boolean get() = this == IDLE || this == JOGGING

    /** A program or a move is running; the phone only watches (PLC: BleProgramRunning). */
    val isProgramRunning: Boolean get() = this == GCODE || this == MOVE_SET_POS

    /** The PLC ignores "power off" from the phone in these states, so the switch is locked. */
    val isMoving: Boolean get() = isProgramRunning || this == JOGGING || this == HOMING

    /** States the app's Stop button can stop (the PLC checks this too). */
    val canStop: Boolean get() = isProgramRunning || this == HOMING

    companion object {
        fun fromCode(code: Int): RobotState? = entries.find { it.code == code }
    }
}

/**
 * Robot status as reported by the ESP32 once a second (and on every change):
 * the line `ST:<state>,<plc>\n`, e.g. `ST:20,1`.
 * [plcOnline] is false when the PLC has stopped writing to the ESP32.
 */
data class RobotStatus(val stateCode: Int, val plcOnline: Boolean) {
    val state: RobotState? get() = RobotState.fromCode(stateCode)

    companion object {
        fun parse(line: String): RobotStatus? {
            if (!line.startsWith("ST:")) return null
            val parts = line.removePrefix("ST:").split(',')
            val code = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: return null
            return RobotStatus(code, plcOnline = parts.getOrNull(1)?.trim() == "1")
        }
    }
}
