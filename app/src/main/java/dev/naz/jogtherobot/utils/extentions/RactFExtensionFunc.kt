package dev.naz.jogtherobot.utils.extentions

import android.graphics.RectF
import androidx.compose.ui.geometry.Size
import dev.naz.jogtherobot.domain.model.camera.LetterboxResult
import dev.naz.jogtherobot.domain.model.camera.LetterboxResultV2
import dev.naz.jogtherobot.domain.model.camera.ReverseMapping
import kotlin.math.max


/*
*   Model input:      1280 x 1280 (640x640)  (square, YOLO)
    ImageProxy:       1280 x 720    (camera frame, 16:9)
    PreviewView:      1080 x 1080   (your UI view)
    Canvas:           1080 x 2294   (full screen overlay)
* */

fun RectF.mapToPreview(
    previewViewSize: Size,
    imageProxySize: Size,
): RectF {


    val scale = max(
        previewViewSize.height/imageProxySize.height,
        previewViewSize.width/imageProxySize.width
    )

    val scaledWidth = imageProxySize.width * scale
    val scaledHeight = imageProxySize.height * scale

    //Offset (crop happens horizontally)
    val dx = (previewViewSize.width - scaledWidth) / 2f
    val dy = (previewViewSize.height - scaledHeight) / 2f


    //logs
    /*d(ImageDebugTag, "mapToPreview: previewViewSize height: ${previewViewSize.height}")
    d(ImageDebugTag, "mapToPreview: previewViewSize width: ${previewViewSize.width}")
    d(ImageDebugTag, "mapToPreview: imageProxySize h: ${imageProxySize.height}")
    d(ImageDebugTag, "mapToPreview: imageProxySize w: ${imageProxySize.width}")

    d(ImageDebugTag, "mapToPreview: scale: $scale")
    d(ImageDebugTag, "mapToPreview: dx: $dx")
    d(ImageDebugTag, "mapToPreview: dy: $dy")*/



    return RectF(
        left * scale + dx,
        top * scale + dy,
        right * scale + dx,
        bottom * scale + dy
    )
}

fun RectF.mapToPreview(
    mapping: ReverseMapping
): RectF {

    return RectF(
        left * mapping.scale + mapping.dx,
        top * mapping.scale + mapping.dy,
        right * mapping.scale + mapping.dx,
        bottom * mapping.scale + mapping.dy
    )
}

fun RectF.mapFromModel(
    letterbox: LetterboxResult
): RectF {

    val modelSize = 640f

    // Step 1: normalized → model pixels
    val left =  left * modelSize
    val top =  top * modelSize
    val right =  right * modelSize
    val bottom =  bottom * modelSize

    // Step 2: remove padding
    val x1 = (left - letterbox.padX) / letterbox.scale
    val y1 = (top - letterbox.padY) / letterbox.scale
    val x2 = (right - letterbox.padX) / letterbox.scale
    val y2 = (bottom - letterbox.padY) / letterbox.scale

    return RectF(x1, y1, x2, y2)
}

fun RectF.mapFromModel(
    letterbox: LetterboxResultV2
): RectF {

    // Step 1: normalized → model pixels
    val left =  left * letterbox.modelSize
    val top =  top * letterbox.modelSize
    val right =  right * letterbox.modelSize
    val bottom =  bottom * letterbox.modelSize

    // Step 2: remove padding
    val x1 = (left - letterbox.padX) / letterbox.scale
    val y1 = (top - letterbox.padY) / letterbox.scale
    val x2 = (right - letterbox.padX) / letterbox.scale
    val y2 = (bottom - letterbox.padY) / letterbox.scale

    return RectF(x1, y1, x2, y2)
}