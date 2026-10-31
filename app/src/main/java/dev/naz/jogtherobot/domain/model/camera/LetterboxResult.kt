package dev.naz.jogtherobot.domain.model.camera

import android.graphics.Bitmap

data class LetterboxResult(
    val bitmap: Bitmap,
    val scale: Float,
    val padX: Float,
    val padY: Float
)


data class LetterboxResultV2(
    val modelSize: Int,
    val scale: Float,
    val padX: Float,
    val padY: Float
)