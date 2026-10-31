package dev.naz.jogtherobot.utils

import android.os.Build
import androidx.compose.ui.geometry.Size
import dev.naz.jogtherobot.domain.model.camera.ReverseMapping
import kotlin.math.max

object Utility {
    fun getReverseMapping(
        previewViewSize: Size,
        imageProxySize: Size
    ): ReverseMapping {
        val scale = max(
            previewViewSize.height / imageProxySize.height,
            previewViewSize.width / imageProxySize.width
        )

        val scaledWidth = imageProxySize.width * scale
        val scaledHeight = imageProxySize.height * scale

        //Offset (crop happens horizontally)
        val dx = (previewViewSize.width - scaledWidth) / 2f
        val dy = (previewViewSize.height - scaledHeight) / 2f

        //logs
        /*
        d(ImageDebugTag, "mapToPreview: scale: $scale")
        d(ImageDebugTag, "mapToPreview: dx: $dx")
        d(ImageDebugTag, "mapToPreview: dy: $dy")*/

        return ReverseMapping(dx,dy,scale)
    }


    fun getDeviceName(): String {
        val manufacturer = Build.MANUFACTURER
        val model = Build.MODEL

        return if (model.startsWith(manufacturer, ignoreCase = true)) {
            model
        } else {
            "$manufacturer $model"
        }
    }
}