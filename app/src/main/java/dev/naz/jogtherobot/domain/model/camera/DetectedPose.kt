package dev.naz.jogtherobot.domain.model.camera

import android.graphics.RectF

data class DetectedPose(
    val boundingBox: RectF,
    val label: String,
    val confidence: Float,
    val classId: Int,
    val keyPoints: List<Keypoint>
){
    override fun toString(): String {
        return "DetectedObject(" +
                "label='$label', " +
                "confidence=${"%.2f".format(confidence)}, " +
                "classId=$classId, " +
                "boundingBox=[left=${boundingBox.left}, top=${boundingBox.top}, right=${boundingBox.right}, bottom=${boundingBox.bottom}], " +
                "keypoints:" + keyPoints.toString() +
                ")"

    }
}

operator fun List<Keypoint>.get(part: BodyPart) = this[part.index]

fun List<Keypoint>.toPose(): Pose {
    require(size == 17) { "Expected 17 keypoints, got $size" }

    return Pose(
        nose = this[BodyPart.NOSE],
        leftEye = this[BodyPart.LEFT_EYE],
        rightEye = this[BodyPart.RIGHT_EYE],
        leftEar = this[BodyPart.LEFT_EAR],
        rightEar = this[BodyPart.RIGHT_EAR],
        leftShoulder = this[BodyPart.LEFT_SHOULDER],
        rightShoulder = this[BodyPart.RIGHT_SHOULDER],
        leftElbow = this[BodyPart.LEFT_ELBOW],
        rightElbow = this[BodyPart.RIGHT_ELBOW],
        leftWrist = this[BodyPart.LEFT_WRIST],
        rightWrist = this[BodyPart.RIGHT_WRIST],
        leftHip = this[BodyPart.LEFT_HIP],
        rightHip = this[BodyPart.RIGHT_HIP],
        leftKnee = this[BodyPart.LEFT_KNEE],
        rightKnee = this[BodyPart.RIGHT_KNEE],
        leftAnkle = this[BodyPart.LEFT_ANKLE],
        rightAnkle = this[BodyPart.RIGHT_ANKLE],
    )
}