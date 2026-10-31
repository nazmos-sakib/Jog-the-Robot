package dev.naz.jogtherobot.utils

object Constants {
    const val MODEL_TFLITE_OLD_FLOAT32 = "yolo26n-pose_float32.tflite"
    const val MODEL_TFLITE_OLD_INT8_FULL = "yolo26n-pose_full_integer_quant.tflite"
    const val MODEL_TFLITE_OLD_INT8 = "yolo26n-pose_integer_quant.tflite"

    //ONNX
    const val MODEL_PATH_26N_POSE_MOBILE = "yolo26n-pose-mobile.onnx"
    const val MODEL_ONNX_FP32 = "yolo26n_pose_fp32.onnx"
    const val MODEL_ONNX_INT8 = "yolo26n_pose_int8.onnx"

    //PyTorch
    const val MODEL_PYTORCH_F32_ORIGINAL = "yolo26n-pose.pt"
    const val MODEL_PYTORCH_F32_OPTIMIZED = "yolo26n-pose-saved-for-lite-interpreter.pt"
    const val MODEL_PYTORCH_F32_CPU = "yolo26n_pytorch_cpu_fp32.ptl"


    // YOLOv8 input size
    const val MODEL_INPUT_SIZE = 640

    // Confidence threshold
    const val CONFIDENCE_THRESHOLD = 0.5f

    // Non-maximum suppression threshold
    const val NMS_THRESHOLD = 0.45f

    // COCO Dataset classes (80 classes)
    val COCO_CLASSES = listOf(
        "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck", "boat",
        "traffic light", "fire hydrant", "stop sign", "parking meter", "bench", "bird", "cat",
        "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra", "giraffe", "backpack",
        "umbrella", "handbag", "tie", "suitcase", "frisbee", "skis", "snowboard", "sports ball",
        "kite", "baseball bat", "baseball glove", "skateboard", "surfboard", "tennis racket",
        "bottle", "wine glass", "cup", "fork", "knife", "spoon", "bowl", "banana", "apple",
        "sandwich", "orange", "broccoli", "carrot", "hot dog", "pizza", "donut", "cake", "chair",
        "couch", "potted plant", "bed", "dining table", "toilet", "tv", "laptop", "mouse",
        "remote", "keyboard", "cell phone", "microwave", "oven", "toaster", "sink", "refrigerator",
        "book", "clock", "vase", "scissors", "teddy bear", "hair drier", "toothbrush"
    )

    const val UiDebugTag = "UI_DEBUG_TAG"
    const val PerformanceDebugTag = "PERFORMANCE_DEBUG_TAG"
    const val ImageDebugTag = "IMAGE_DEBUG_TAG"
    const val TFModelDebugTag = "TF_MODEL_DEBUG_TAG"
    const val ONNXModelDebugTag = "ONNX_MODEL_DEBUG_TAG"
    const val PyTorchModelDebugTag = "PyTORCH_MODEL_DEBUG_TAG"
    const val BASE_URL = "http://192.168.2.119:5000"
    const val NetworkDebugTag = "NETWORK_DEBUG_TAG"
    const val CrashDebugTag = "CRASH_DEBUG_TAG"
    const val ModelOutpuDebugTag = "MODEL_OUTPUT_DEBUG_TAG"

    const val FLOATS_PER_POSE = 56
    const val NUM_KEYPOINTS = 17

}