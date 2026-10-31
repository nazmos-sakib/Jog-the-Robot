package dev.naz.jogtherobot.domain.model.camera

import android.graphics.RectF

data class DetectedObject(
    val boundingBox: RectF,
    val label: String,
    val confidence: Float,
    val classId: Int
){
    override fun toString(): String {
        return "DetectedObject(" +
                "label='$label', " +
                "confidence=${"%.2f".format(confidence)}, " +
                "classId=$classId, " +
                "boundingBox=[left=${boundingBox.left}, top=${boundingBox.top}, right=${boundingBox.right}, bottom=${boundingBox.bottom}]" +
                ")"
    }
}


