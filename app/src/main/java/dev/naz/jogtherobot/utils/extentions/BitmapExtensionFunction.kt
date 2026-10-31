package dev.naz.jogtherobot.utils.extentions

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.CameraSelector
import java.io.ByteArrayOutputStream

fun Bitmap.rotateBitmap(rotation: Int,cameraSelector: CameraSelector): Bitmap {
    val matrix = Matrix().apply {
        postRotate(rotation.toFloat())
        if (cameraSelector==CameraSelector.DEFAULT_FRONT_CAMERA){
            postScale(-1f,1f,width/2f,height/2f)
        }
    }
    return Bitmap.createBitmap(
        this, 0, 0,
        width, height,
        matrix, true
    )
}

fun Bitmap.bitmapToJpegBytes( ): ByteArray {
    val stream = ByteArrayOutputStream()
    compress(Bitmap.CompressFormat.JPEG, 90, stream)
    return stream.toByteArray()
}
