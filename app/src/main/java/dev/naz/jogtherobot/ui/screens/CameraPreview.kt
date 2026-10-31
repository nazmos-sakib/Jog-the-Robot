package dev.naz.jogtherobot.ui.screens

import android.graphics.Rect
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.Executors
//Clean
@Composable
fun CameraPreview(
    modifier: Modifier = Modifier,
    outputImageProxyFormat: Int,
    camera: CameraSelector,
    aspectRation: Int,
    onFrame: (ImageProxy, PreviewView) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    // Single background thread for analysis
    val cameraExecutor = remember {
        Executors.newSingleThreadExecutor()
    }

    val cameraProviderFuture = remember {
        ProcessCameraProvider.getInstance(context)
    }

    //resolution
    val resolutionSelector = ResolutionSelector.Builder()
        .setAspectRatioStrategy(
            AspectRatioStrategy(
                aspectRation,
                AspectRatioStrategy.FALLBACK_RULE_AUTO
            )
        )
        .build()

    DisposableEffect(lifecycleOwner) {

        val executor = ContextCompat.getMainExecutor(context)

        cameraProviderFuture.addListener({

            val cameraProvider = cameraProviderFuture.get()

            // 🔹 Preview
            val preview = Preview.Builder()
                .setResolutionSelector(resolutionSelector)
                .build()
            if (previewView.isAttachedToWindow) {
                preview.setSurfaceProvider(previewView.surfaceProvider)
            }
            //preview.setSurfaceProvider(previewView.surfaceProvider)


            // 🔹 ImageAnalysis (optimized)
            val analyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setResolutionSelector(resolutionSelector)
                .setOutputImageFormat(outputImageProxyFormat)
                .setTargetRotation(previewView.display.rotation)  //setting the same rotation as previewView
                .build()

            //analyzer.targetRotation = preview.targetRotation

            analyzer.setAnalyzer(cameraExecutor) { imageProxy ->
                try {
                    val width = imageProxy.width      // 640 /
                    val height = imageProxy.height    // 480
                    //Log.d(ImageDebugTag, "CameraPreview: ${imageProxy.imageInfo.rotationDegrees}")
                    //Log.d(ImageDebugTag, "CameraPreview: before crop resolution: width:${imageProxy.width} height: ${imageProxy.height}")
                    //Log.d(ImageDebugTag, "CameraPreview: ImageProxy format:${imageProxy.format}")
                    val size = minOf(width, height)   // 480

                    val left = (width - size) / 2     // 80
                    val top = (height - size) / 2     // 0

                    val cropRect = Rect(
                        left,
                        top,
                        left + size,
                        top + size
                    )

                    // 🔥 THIS IS THE KEY (no bitmap involved)
                    //does NOT physically crop the pixel data.
                    //It updates metadata inside imageProxy
                    imageProxy.setCropRect(cropRect)
                    // Send raw frame (no conversion here)
                    onFrame(imageProxy,previewView)



                } catch (e: Exception) {
                    //Log.d(CrashDebugTag, "==================CameraPreview:analyzer crashed==================")
                    e.printStackTrace()
                    imageProxy.close()
                } finally {
                    imageProxy.close() // 🔴 MUST always close
                }
            }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    //CameraSelector.DEFAULT_BACK_CAMERA,
                    camera,
                    preview,
                    analyzer
                )

            } catch (e: Exception) {
                e.printStackTrace()
            }

        }, executor)

        onDispose {
            cameraExecutor.shutdown()
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = modifier
            .fillMaxSize()
            //when no aspect ratio is set previewView is width:1080 height: 2294
            //.aspectRatio(1f) //->1080x1080
            .aspectRatio((4f / 3f))
    )


}