package dev.naz.jogtherobot.data.repository


import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.util.Log
import androidx.core.graphics.scale
import kotlin.math.min
import androidx.core.graphics.createBitmap
import dev.naz.jogtherobot.domain.model.camera.DetectedPose
import dev.naz.jogtherobot.domain.model.camera.LetterboxResult
import dev.naz.jogtherobot.domain.model.camera.ReverseMapping
import dev.naz.jogtherobot.data.datasource.ONNXModelDataSource
import dev.naz.jogtherobot.domain.model.InferenceConfiguration
import dev.naz.jogtherobot.utils.Constants
import dev.naz.jogtherobot.utils.YOLOPostprocessor
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ONNXPoseDetectionRepository(
    private val modelDataSource: ONNXModelDataSource
) : PoseDetectionRepository {
    private var initialized = false
    private val size = Constants.MODEL_INPUT_SIZE
    private val channelSize = size * size

    private val floatBuffer = ByteBuffer
        .allocateDirect(4 * 3 * size * size)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

    private val pixels = IntArray(size * size)

    override fun initialize(configuration: InferenceConfiguration) {
        if (initialized) return
        modelDataSource.loadModel(configuration)
        initialized = true
        Log.d("ONNX_REPOSITORY", "model loaded successfully")
    }

    /**
     * Initialize model
     */
    override suspend fun detectPose(bitmap: Bitmap,revMapping: ReverseMapping,infConfig:InferenceConfiguration): List<DetectedPose> {
        initialize(infConfig)

        return try {
            // Preprocess image
            val result = prepareInput(bitmap, modelDataSource.getEnv(), Constants.MODEL_INPUT_SIZE)

            val inputBuffer = result.first

            // Run inference
            val outputs = modelDataSource.run(inputBuffer)

            //return statement
            // Postprocess results
            YOLOPostprocessor.parseOutputShape300x57(
                outputs,
                result.second,
                revMapping
            )

        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        } finally {
            bitmap.recycle()
        }
    }


    fun prepareInput(bitmap: Bitmap,env:OrtEnvironment,inputSize: Int): Pair<OnnxTensor, LetterboxResult> {
        val result = letterbox(bitmap, inputSize)

        //val byteBuffer = createBuffer(4*3*inputSize*inputSize)

        result.bitmap.getPixels(pixels, 0, inputSize, 0, 0,
            inputSize, inputSize
        )

        for (i in pixels.indices) {
            val pixel = pixels[i]

            val r = (pixel shr 16 and 0xFF) / 255f
            val g = (pixel shr 8 and 0xFF) / 255f
            val b = (pixel and 0xFF) / 255f

            floatBuffer.put(i, r)
            floatBuffer.put(i + channelSize, g)
            floatBuffer.put(i + 2 * channelSize, b)
        }
        floatBuffer.rewind()
        //resizedBitmap.recycle()
        return Pair(
            OnnxTensor.createTensor(
                env,
                floatBuffer,
                longArrayOf(1, 3, 640, 640)),
            result
        )
    }




    fun letterbox(bitmap: Bitmap, size: Int = 640): LetterboxResult {
        val width = bitmap.width
        val height = bitmap.height

        val scale = min(size / width.toFloat(), size / height.toFloat())

        val newWidth = (width * scale).toInt()
        val newHeight = (height * scale).toInt()

        val resized = bitmap.scale(newWidth, newHeight)

        val output = createBitmap(size, size)
        val canvas = Canvas(output)

        val padX = (size - newWidth) / 2f
        val padY = (size - newHeight) / 2f

        canvas.drawColor(Color.BLACK)
        canvas.drawBitmap(resized, padX, padY, null)

        return LetterboxResult(output, scale, padX, padY)
    }

    override fun release() {
        modelDataSource.close()
        initialized = false
    }


}