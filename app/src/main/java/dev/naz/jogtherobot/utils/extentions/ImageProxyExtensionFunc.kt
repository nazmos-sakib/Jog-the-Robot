package dev.naz.jogtherobot.utils.extentions

import android.graphics.Bitmap
import androidx.camera.core.ImageProxy
import androidx.core.graphics.createBitmap

/**
 * Extension function to convert ImageProxy
 */

fun ImageProxy.rgbToBitmap(): Bitmap {
    val buffer = planes[0].buffer
    val bitmap = createBitmap(width, height)
    bitmap.copyPixelsFromBuffer(buffer)
    return bitmap
}