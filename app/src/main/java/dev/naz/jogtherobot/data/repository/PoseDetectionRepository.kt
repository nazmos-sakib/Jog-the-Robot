package dev.naz.jogtherobot.data.repository

import android.graphics.Bitmap
import dev.naz.jogtherobot.domain.model.camera.DetectedPose
import dev.naz.jogtherobot.domain.model.camera.ReverseMapping
import dev.naz.jogtherobot.domain.model.InferenceConfiguration

interface PoseDetectionRepository {
    fun initialize(configuration: InferenceConfiguration)

    suspend fun detectPose(
        bitmap: Bitmap,
        revMapping: ReverseMapping,
        infConfig: InferenceConfiguration
    ): List<DetectedPose>

    fun release()
}
