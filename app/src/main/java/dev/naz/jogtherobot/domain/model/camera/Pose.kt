package dev.naz.jogtherobot.domain.model.camera

data class Pose(
    val nose: Keypoint,
    val leftEye: Keypoint,
    val rightEye: Keypoint,
    val leftEar: Keypoint,
    val rightEar: Keypoint,
    val leftShoulder: Keypoint,
    val rightShoulder: Keypoint,
    val leftElbow: Keypoint,
    val rightElbow: Keypoint,
    val leftWrist: Keypoint,
    val rightWrist: Keypoint,
    val leftHip: Keypoint,
    val rightHip: Keypoint,
    val leftKnee: Keypoint,
    val rightKnee: Keypoint,
    val leftAnkle: Keypoint,
    val rightAnkle: Keypoint,
){
    override fun toString(): String {
        return """
            Pose(
              nose=$nose,
              leftEye=$leftEye, rightEye=$rightEye,
              leftEar=$leftEar, rightEar=$rightEar,
              leftShoulder=$leftShoulder, rightShoulder=$rightShoulder,
              leftElbow=$leftElbow, rightElbow=$rightElbow,
              leftWrist=$leftWrist, rightWrist=$rightWrist,
              leftHip=$leftHip, rightHip=$rightHip,
              leftKnee=$leftKnee, rightKnee=$rightKnee,
              leftAnkle=$leftAnkle, rightAnkle=$rightAnkle
            )
        """.trimIndent()
    }
}

/*
* Pose(
   nose=(x=281.250, y=-107.438, c=0.859),
   leftEye=(x=256.781, y=-147.375, c=0.457), rightEye=(x=329.344, y=-161.438, c=0.765),
   leftEar=(x=258.750, y=-102.375, c=0.052), rightEar=(x=456.188, y=-133.875, c=0.574),
   leftShoulder=(x=233.438, y=244.969, c=0.958), rightShoulder=(x=641.250, y=153.563, c=0.967),
   leftElbow=(x=36.000, y=637.313, c=0.583), rightElbow=(x=572.625, y=533.250, c=0.549),
   leftWrist=(x=198.844, y=487.688, c=0.511), rightWrist=(x=262.688, y=563.625, c=0.041),
   leftHip=(x=405.000, y=979.875, c=0.644), rightHip=(x=725.063, y=950.625, c=0.693),
   leftKnee=(x=295.875, y=954.000, c=0.004), rightKnee=(x=707.625, y=949.500, c=0.008),
   leftAnkle=(x=784.125, y=1022.625, c=0.001), rightAnkle=(x=480.938, y=995.625, c=0.001)
 )
* */


fun Pose.jogCommandMapping(){

}