package dev.naz.jogtherobot.data.datasource


import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import ai.onnxruntime.ValueInfo
import ai.onnxruntime.providers.NNAPIFlags
import android.content.Context
import android.util.Log
import dev.naz.jogtherobot.domain.model.InferenceConfiguration
import dev.naz.jogtherobot.utils.Constants
import dev.naz.jogtherobot.utils.Constants.ONNXModelDebugTag
import dev.naz.jogtherobot.utils.Constants.TFModelDebugTag
import dev.naz.jogtherobot.utils.DelegateOption
import dev.naz.jogtherobot.utils.PrecisionOption
import java.util.Arrays
import java.util.EnumSet

class ONNXModelDataSource(private val context: Context) {

    private val env = OrtEnvironment.getEnvironment()
    private var session: OrtSession? = null

    /**
     * Load ONNX  model from assets
     * input shape: [1, 640, 640, 3]
     * output shape: [1, 300, 57]
     * data type: FLOAT32
     */

    fun loadModel(configuration: InferenceConfiguration) {

        val modelPath = when (configuration.precision) {
            PrecisionOption.FP32 -> Constants.MODEL_ONNX_FP32
            else -> Constants.MODEL_ONNX_INT8
        }

        val modelBytes = context.assets.open(modelPath).readBytes()

        val options = when (configuration.delegate) {
            DelegateOption.NNAPI -> OrtSession.SessionOptions().apply {
                addNnapi(
                    EnumSet.of(
                        NNAPIFlags.USE_FP16,
                        NNAPIFlags.CPU_DISABLED
                    )
                )
            }

            //all default falls into CPU
            else -> OrtSession.SessionOptions().apply {
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
        }
        session = env.createSession(modelBytes, options)
        logModelInfo()
    }



    fun run(inputTensor: OnnxTensor): Array<FloatArray> {
        val inputs = mapOf(
            "images" to inputTensor   // ⚠️ confirm input name using Netron
        )

        val outputs = session?.run(inputs)!!

        val outputTensor  = outputs[0].value as Array<Array<FloatArray>>
        return outputTensor[0]
    }

    fun getEnv(): OrtEnvironment {
        return env
    }

    private fun logModelInfo() {
        session?.inputInfo?.forEach { (_, nodeInfo) ->
            logTensorInfo("Input", nodeInfo.info)
        }

        session?.outputInfo?.forEach { (_, nodeInfo) ->
            logTensorInfo("Output", nodeInfo.info)
        }
    }

    private fun logTensorInfo(name: String, valueInfo: ValueInfo) {
        Log.d(ONNXModelDebugTag, "=== $name INFO ===")

        if (valueInfo !is TensorInfo) {
            Log.d(ONNXModelDebugTag, "logTensorInfo: non-tensor ONNX value: $valueInfo")
            return
        }

        val shape = valueInfo.shape
        val dataCapacity = calculateDataCapacity(valueInfo)

        Log.d(ONNXModelDebugTag, "logTensorInfo: shape: ${Arrays.toString(shape)}")
        Log.d(ONNXModelDebugTag, "logTensorInfo: data type: ${valueInfo.onnxType}")
        Log.d(ONNXModelDebugTag, "logTensorInfo: data capacity: $dataCapacity")
        Log.d(ONNXModelDebugTag, "logTensorInfo: Quantization Scale: Not exposed by ONNX Runtime TensorInfo")
        Log.d(ONNXModelDebugTag, "logTensorInfo: Quantization zero Point: Not exposed by ONNX Runtime TensorInfo")
    }

    private fun calculateDataCapacity(tensorInfo: TensorInfo): String {
        val numElements = tensorInfo.numElements
        if (numElements < 0) return "Unknown because shape contains dynamic dimensions"

        return "${numElements * tensorInfo.type.size} bytes"
    }

    fun close() {
        session?.close()
        session = null
    }

}