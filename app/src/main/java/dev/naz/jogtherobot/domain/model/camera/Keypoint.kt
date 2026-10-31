package dev.naz.jogtherobot.domain.model.camera

data class Keypoint(
    val kx: Float,
    val ky: Float,
    val kc: Float,
){
    override fun toString(): String {
        return "(x=${kx.format()}, y=${ky.format()}, c=${kc.format()})"
    }
    private fun Float.format() = "%.3f".format(this)
}



fun  Keypoint.deNormalize(): Keypoint {
    return Keypoint(
        kx * 640,
        ky * 640,
        kc
    )
}

fun  Keypoint.mapFromModel(
    letterbox: LetterboxResult
): Keypoint {
    // Step 1: remove letterbox
    val x = (kx - letterbox.padX) / letterbox.scale
    val y = (ky - letterbox.padY) / letterbox.scale
    return Keypoint(x,y,kc)
}

fun  Keypoint.mapFromModel(
    letterbox: LetterboxResultV2
): Keypoint {
    // Step 1: remove letterbox
    val x = (kx - letterbox.padX) / letterbox.scale
    val y = (ky - letterbox.padY) / letterbox.scale
    return Keypoint(x,y,kc)
}

fun  Keypoint.mapToPreview(
    mapping: ReverseMapping
):  Keypoint {
    // Step 2: revers mapping to previewView

    return Keypoint(
        kx * mapping.scale + mapping.dx,
        ky * mapping.scale + mapping.dy,
        kc
    )
}
