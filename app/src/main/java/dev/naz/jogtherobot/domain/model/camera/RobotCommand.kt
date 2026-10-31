package dev.naz.jogtherobot.domain.model.camera


enum class RobotCommand {
    NEUTRAL,
    STOP,
    START,
    //EMERGENCY_STOP,
    FORWARD,
    //FORWARD_SLOW,
    BACKWARD,
    TURN_LEFT,
    TURN_RIGHT,
    NONE,
    NO_PERSON
}

enum class RobotArmCommand {
    NEUTRAL,
    A1, //STOP,
    A2, //START,
    //EMERGENCY_STOP,
    A3, //FORWARD,
    //FORWARD_SLOW,
    A4, //BACKWARD,
    TURN_LEFT,
    TURN_RIGHT,
    NONE,
    NO_PERSON
}