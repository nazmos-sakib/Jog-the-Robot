package dev.naz.jogtherobot.ui.screens

import android.graphics.Paint
import android.graphics.RectF
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.naz.jogtherobot.bluetooth.BtDevice
import dev.naz.jogtherobot.bluetooth.RobotStatus
import dev.naz.jogtherobot.domain.model.camera.DetectedPose
import dev.naz.jogtherobot.domain.model.camera.Keypoint
import dev.naz.jogtherobot.ui.theme.JogTheRobotTheme
import dev.naz.jogtherobot.ui.viewmodel.CameraViewModel
import dev.naz.jogtherobot.utils.ChoreographerFPSMonitor
import dev.naz.jogtherobot.utils.Constants.PerformanceDebugTag
import dev.naz.jogtherobot.utils.Utility
import dev.naz.jogtherobot.utils.extentions.rgbToBitmap
import dev.naz.jogtherobot.utils.extentions.rotateBitmap
import kotlinx.coroutines.delay

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** The ESP32 sends the robot status every second; older than this = no status. */
private const val STATUS_TIMEOUT_MS = 3_000L

@Composable
fun GestureJogScreen(
    viewModel: CameraViewModel,
    onSend: suspend (String) -> Boolean,
    incoming: Flow<ByteArray>,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {

    val detectedObjects by viewModel.detectedObjects.collectAsStateWithLifecycle()
    val isProcessing by viewModel.isProcessing.collectAsStateWithLifecycle()
    val previewViewFps by viewModel.previewViewFps.collectAsStateWithLifecycle()
    val imageProxyFps by viewModel.imageProxyFps.collectAsStateWithLifecycle()
    val inferenceFps by viewModel.inferenceFps.collectAsStateWithLifecycle()
    val cmd by viewModel.command.collectAsStateWithLifecycle()
    val configuration by viewModel.configuration.collectAsStateWithLifecycle()

    var previewViewSize: Size by remember { mutableStateOf(Size(0f,0f)) }
    var imageProxySize: Size by remember { mutableStateOf(Size(0f,0f)) }

    //val outputImageProxyFormat = ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888
    val outputImageProxyFormat = ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888
    //val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

    val imageProxyAspectRation = AspectRatio.RATIO_4_3
    //val imageProxyAspectRation = AspectRatio.RATIO_16_9


    //--------concerning robot
    val selectedAxis by viewModel.selectedAxis.collectAsStateWithLifecycle()
    val selectedDirection by viewModel.selectedDirection.collectAsStateWithLifecycle()

    // Robot status from the ESP32. null = nothing received for STATUS_TIMEOUT_MS.
    var status by remember { mutableStateOf<RobotStatus?>(null) }
    var lastStatusAt by remember { mutableStateOf(0L) }
    LaunchedEffect(incoming) {
        // BLE notifications can split or join lines, so collect bytes until '\n'.
        val buffer = StringBuilder()
        incoming.collect { bytes ->
            buffer.append(String(bytes, Charsets.UTF_8))
            while (true) {
                val end = buffer.indexOf("\n")
                if (end < 0) break
                val line = buffer.substring(0, end).trim()
                buffer.delete(0, end + 1)
                RobotStatus.parse(line)?.let {
                    status = it
                    lastStatusAt = System.currentTimeMillis()
                }
            }
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            if (System.currentTimeMillis() - lastStatusAt > STATUS_TIMEOUT_MS) status = null
        }
    }
    viewModel.canJog = status?.plcOnline == true && status?.state?.canJog == true





    //================Preview FPS monitor
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    ChoreographerFPSMonitor.startFpsMonitor(viewModel)
                }

                Lifecycle.Event.ON_PAUSE -> {
                    ChoreographerFPSMonitor.stopFpsMonitor()
                }

                else -> Unit
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            ChoreographerFPSMonitor.stopFpsMonitor()
        }
    } //ends================Preview FPS monitor

    Box(modifier = Modifier
        .fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        CameraPreview(
            modifier = Modifier.align(Alignment.BottomCenter),
            outputImageProxyFormat = outputImageProxyFormat,
            camera = cameraSelector,
            aspectRation = imageProxyAspectRation,
            onFrame = { _imgProxy, _previewView ->

                //display how many times it's been call and how many imageProxy has been discarded
                viewModel.updateImageProxyFPS()

                //for proper bounding box =========================
                previewViewSize = Size(_previewView.width.toFloat(), _previewView.height.toFloat())
                imageProxySize = if (_imgProxy.imageInfo.rotationDegrees in intArrayOf(0, 180)) {
                    Size(_imgProxy.width.toFloat(), _imgProxy.height.toFloat())
                } else {
                    Size(_imgProxy.height.toFloat(), _imgProxy.width.toFloat())
                }
                //end bounding box pre calculation =========================

                //skip imageProxy if its already in process
                if (!isProcessing) {
                    //val jpegBytes = _imgProxy.yuvToJpegByte(quality = 70)
                    val rotatedBitMap =
                        _imgProxy.rgbToBitmap()
                            .rotateBitmap(_imgProxy.imageInfo.rotationDegrees, cameraSelector)

                    viewModel.processFrame(
                        rotatedBitMap,
                        Utility.getReverseMapping(previewViewSize, imageProxySize)
                    )
                }


            }
        )


        // Overlay canvas for bounding boxes
        Canvas(modifier = Modifier
            .fillMaxSize()
        ) {
            //if previewView is set to fillMaxSize without an aspect ratio then both will have -> width:1080 height: 2294
            detectedObjects.forEach { detection ->
                //Log.d(UiDebugTag, "CameraScreen: $detection")
                drawBoundingBox(
                    detection = detection,
                    canvasSize = size,
                    previewViewSize = previewViewSize,
                    imageProxySize = imageProxySize
                )
            }
        }

        //extra information about the process

        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.Top,
        ) {
            Column(
                modifier = Modifier.weight(.6f),
            ) {
                Text(
                    text = buildAnnotatedString {
                        withStyle(style = SpanStyle(color = Color.White)) {
                            append("Device Name: ")
                        }
                        withStyle(style = SpanStyle(color = Color.Green)) {
                            append(Utility.getDeviceName())
                        }
                    },
                    modifier = Modifier
                        //.align(Alignment.TopStart)
                        .padding(16.dp,0.dp) ,
                    color = Color.White,
                    fontSize = 14.sp
                )
                Text(
                    text = buildAnnotatedString {
                        withStyle(style = SpanStyle(color = Color.Red)) {
                            append("${configuration.runtime.label}-")
                        }
                        withStyle(style = SpanStyle(color = Color.Green)) {
                            append("${configuration.delegate.label}-")
                        }
                        withStyle(style = SpanStyle(color = Color.White)) {
                            append(configuration.precision.label)
                        }
                    },
                    modifier = Modifier
                        //.align(Alignment.TopStart)
                        .padding(16.dp,0.dp) ,
                    color = Color.White,
                    fontSize = 14.sp
                )
                Text(
                    text = "PreviewView FPS: ${"%.1f".format(previewViewFps)}",
                    modifier = Modifier
                        //.align(Alignment.TopStart)
                        .padding(16.dp,0.dp),
                    color = Color.White,
                    fontSize = 14.sp
                )
                Text(
                    text = "ImageProxy FPS: ${"%.1f".format(imageProxyFps)}",
                    modifier = Modifier
                        //.align(Alignment.TopStart)
                        .padding(16.dp,0.dp),
                    color = Color.White,
                    fontSize = 14.sp
                )
                Text(
                    text = "Inference FPS: ${"%.1f".format(inferenceFps)}",
                    modifier = Modifier
                        //.align(Alignment.TopStart)
                        .padding(16.dp,0.dp),
                    color = Color.White,
                    fontSize = 14.sp
                )
            }

            VerticalDivider(
                modifier = Modifier
                    .padding(top = 20.dp)
                    .fillMaxHeight(.13f)
                    .width(1.dp),
                color = Color.Gray
            )

            Box(
                modifier = Modifier.weight(.5f).height(150.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier.padding(bottom = 23.dp).wrapContentHeight()
                ) {
                    Box(
                        modifier = Modifier
                            //.padding(vertical = 5.dp)
                            .wrapContentHeight()
                            .fillMaxWidth()
                            .background(Color(0xFFF5F5F5))
                            .weight(.7f)
                            .padding(vertical = 3.dp),

                    ) {

                        CircularProgressIndicator(
                            modifier = Modifier
                                .align(Alignment.CenterStart)
                                .padding(0.dp)
                                .padding(start = 5.dp)
                                .alpha(
                                    if (isProcessing)1f else 0f
                                )
                        )

                        Text(
                            text = cmd.name,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .fillMaxWidth(),
                            color = Color(0xFF1C1B1F),
                            textAlign = TextAlign.Center
                        )
                    }
                    Box(
                        modifier = Modifier
                            //.padding(vertical = 5.dp)
                            .wrapContentHeight()
                            .fillMaxWidth()
                            .background(Color(0xFFF5F5F5))
                            .weight(.7f)
                    ) {
                        when {
                            status == null -> "No status from the ESP32"
                            status?.plcOnline != true -> "PLC not connected to the ESP32"
                            status?.state == null -> "Unknown PLC state ${status!!.stateCode}"
                            else -> status?.state?.label
                        }?.let {
                            Text(
                                text = it,
                                modifier = Modifier
                                    //.padding(horizontal = 10.dp ,vertical = 50.dp)
                                    //.align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                                    //.background(Color(0xFFF5F5F5))
                                    ,
                                color = Color(0xFF1C1B1F),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
                /*

                // FPS and processing indicator
                if (isProcessing) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .padding(16.dp)
                            .align(Alignment.TopCenter)
                    )
                }
                Text(
                    text = cmd.name,
                    modifier = Modifier
                        .padding(horizontal = 10.dp ,vertical = 50.dp)
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Color(0xFFF5F5F5)),
                    color = Color(0xFF1C1B1F),
                    textAlign = TextAlign.Center
                )*/
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min).align(Alignment.BottomCenter),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically,
        ){
            Box(
                modifier = Modifier.fillMaxHeight().weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = buildAnnotatedString {
                        withStyle(style = SpanStyle(color = Color.White)) {
                            append("Selected Axis: ")
                        }
                        withStyle(style = SpanStyle(color = Color.Green)) {
                            append(selectedAxis?.code?:"None")
                        }

                    },
                    modifier = Modifier
                        //.align(Alignment.TopStart)
                        .padding(16.dp,0.dp),
                    color = Color.White,
                    fontSize = 14.sp
                )
            }
            VerticalDivider(
                modifier = Modifier
                    .padding(vertical = 4.dp)
                    .height(100.dp)
                    .width(1.dp)
                    .weight(.2f),
                color = Color.Gray
            )
            Box(
                modifier = Modifier.fillMaxHeight().weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = buildAnnotatedString {
                        withStyle(style = SpanStyle(color = Color.White)) {
                            append("Moving towards: ")
                        }
                        withStyle(style = SpanStyle(color = Color.Green)) {
                            append(selectedDirection?.code?:"None")
                        }

                    },
                    modifier = Modifier
                        //.align(Alignment.TopStart)
                        .padding(16.dp,0.dp),
                    color = Color.White,
                    fontSize = 14.sp
                )
            }
        }

    }
}

//extension function
private fun DrawScope.drawBoundingBox(
    detection: DetectedPose,
    canvasSize: Size,
    previewViewSize: Size,
    imageProxySize: Size,
) {
    if (previewViewSize.width == 0f || previewViewSize.height == 0f) return

    //tune the canvas size and previewView size. and set an offset
    // divided by 2 as preview is in center and top and bottom has the same offset
    val previewTopOffset = (canvasSize.height - previewViewSize.height) / 2f

    val bbox = detection.boundingBox
        //.mapToPreview( previewViewSize, imageProxySize)
        /*.apply {
            offset(0f, previewTopOffset)
        }*/
        .let {
            RectF(it.left, it.top + previewTopOffset, it.right, it.bottom + previewTopOffset)
        }

    //Log.d(ImageDebugTag, "CameraScreen:drawBoundingBox: ract: $bbox")
    val left  = bbox.left
    val top = bbox.top
    val right = bbox.right
    val bottom = bbox.bottom



    // Draw rectangle
    //bounding box
    drawRect(
        color = Color.Green,
        topLeft = Offset(left, top),
        size = Size(right - left, bottom - top),
        style = Stroke(width = 6f)
    )



    // Draw label background
    val labelText = "${detection.label} ${"%.1f".format(detection.confidence * 100)}%"

    // Draw label
    drawContext.canvas.nativeCanvas.apply {
        val paint = Paint().apply {
            color = android.graphics.Color.GREEN
            textSize = 24f
            isAntiAlias = true
            style = Paint.Style.FILL
        }

        drawText(
            labelText,
            left + ((right-left)/2),
            top - 5,
            paint
        )
    }

    detection.keyPoints.forEach { point->

        val screenX = point.kx
        val screenY = point.ky  + previewTopOffset
        val keypointPaint = Paint().apply {
            color = android.graphics.Color.GREEN
            style = Paint.Style.FILL
            strokeWidth = 8f
        }

        drawCircle(
            color = Color.Green,
            radius = 6f,
            center = Offset(screenX, screenY)
        )
    }

    drawSkeleton(detection.keyPoints,previewViewSize,previewTopOffset)

    //drawPoseWithLabels(detection.keyPoints.toPose())
}

// --- DRAW SKELETON ---
private fun DrawScope.drawSkeleton(
    points: List<Keypoint>,
    previewViewSize: Size,
    previewTopOffset: Float
){

    //
    val skeleton = listOf(
        0 to 1, 0 to 2,
        1 to 3, 2 to 4,
        5 to 6,
        5 to 7, 7 to 9,
        6 to 8, 8 to 10,
        5 to 11, 6 to 12,
        11 to 12,
        11 to 13, 13 to 15,
        12 to 14, 14 to 16
    )

    skeleton.forEach { (i1, i2) ->

        val kp1 = points.getOrNull(i1)
        val kp2 = points.getOrNull(i2)

        if (kp1 == null || kp2 == null) return@forEach
        if (kp1.kc < 0.5f || kp2.kc < 0.5f) return@forEach

        val x1 = kp1.kx
        val y1 = kp1.ky  + previewTopOffset

        val x2 = kp2.kx
        val y2 = kp2.ky  + previewTopOffset

        drawLine(
            color = Color.Yellow,
            start = Offset(x1, y1),
            end = Offset(x2, y2),
            strokeWidth = 3f
        )
    }
}

