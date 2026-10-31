package dev.naz.jogtherobot.utils

enum class DelegateOption(val label: String) {
    CPU("CPU"),
    GPU("GPU"),
    NNAPI("NNAPI"),
    VULKAN("Vulkan")
}

enum class PrecisionOption(val label: String) {
    FP32("FP32"),
    INT8("INT8"),
    HALF_INT8("HALF_INT8")
}

enum class RuntimeOption(
    val label: String,
    val supportedDelegates: List<DelegateOption>,
    val supportedPrecisions: List<PrecisionOption>
) {
    TFLITE(
        label = "TFLite",
        supportedDelegates = listOf(
            DelegateOption.CPU,
            DelegateOption.GPU,
            DelegateOption.NNAPI
        ),
        supportedPrecisions = listOf(
            PrecisionOption.FP32,
            PrecisionOption.HALF_INT8,
            PrecisionOption.INT8,
        )
    ),

    ONNX(
        label = "ONNX Runtime",
        supportedDelegates = listOf(
            DelegateOption.CPU,
            //DelegateOption.GPU,
            DelegateOption.NNAPI
        ),
        supportedPrecisions = listOf(
            PrecisionOption.FP32,
            PrecisionOption.INT8
        )
    ),

    NCNN(
        label = "NCNN",
        supportedDelegates = listOf(
            DelegateOption.CPU,
            DelegateOption.VULKAN
        ),
        supportedPrecisions = listOf(
            PrecisionOption.FP32
        )
    ),

    PYTORCH(
        label = "PyTorch Mobile",
        supportedDelegates = listOf(
            DelegateOption.CPU,
            DelegateOption.GPU
        ),
        supportedPrecisions = listOf(
            PrecisionOption.FP32
        )
    )
}