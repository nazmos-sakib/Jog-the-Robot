package dev.naz.jogtherobot.ui.viewmodel


import android.graphics.Bitmap
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.internal.isLiveLiteralsEnabled
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.naz.jogtherobot.domain.model.InferenceConfiguration
import dev.naz.jogtherobot.domain.model.camera.DetectedPose
import dev.naz.jogtherobot.domain.model.camera.ReverseMapping
import dev.naz.jogtherobot.domain.model.camera.RobotCommand
import dev.naz.jogtherobot.domain.model.camera.toPose
import dev.naz.jogtherobot.domain.usecase.GestureDetector
import dev.naz.jogtherobot.utils.Constants.PerformanceDebugTag
import dev.naz.jogtherobot.utils.Constants.UiDebugTag
import dev.naz.jogtherobot.utils.DelegateOption
import dev.naz.jogtherobot.utils.FpsCounter
import dev.naz.jogtherobot.utils.PrecisionOption
import dev.naz.jogtherobot.utils.RuntimeOption
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.naz.jogtherobot.bluetooth.Axis
import dev.naz.jogtherobot.bluetooth.BluetoothController
import dev.naz.jogtherobot.bluetooth.BtDevice
import dev.naz.jogtherobot.bluetooth.JogDirection
import dev.naz.jogtherobot.bluetooth.JogProtocol
import dev.naz.jogtherobot.data.repository.ONNXPoseDetectionRepository
import dev.naz.jogtherobot.ui.screens.RobotModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * How far the drawing turns per repeated command. The PLC jogs at about
 * 10 deg/s and the command repeats 10 times a second, so 1 deg per repeat.
 * The drawing is only an estimate, not the robot's measured position.
 */
private const val DRAWING_DEG_PER_REPEAT = 1f

/**
 * The jog keeps running only while a frame showed the jog gesture within
 * this time. Must be longer than one inference (check "Inference FPS":
 * at 5 fps or more, 400 ms is fine).
 */
private const val GESTURE_TIMEOUT_MS = 400L

@HiltViewModel
class CameraViewModel @Inject constructor(
    private val repository: ONNXPoseDetectionRepository,
    //private val gestureUseCase: GestureToCommandUseCase,
    private val gestureDetector: GestureDetector
) : ViewModel() {

    private val inferenceFpsCounter = FpsCounter()
    private val imageProxyFpsCounter = FpsCounter()

    private val _command = MutableStateFlow(RobotCommand.NONE)
    val command: StateFlow<RobotCommand> = _command

    private val _detectedObjects = MutableStateFlow<List<DetectedPose>>(emptyList())
    val detectedObjects: StateFlow<List<DetectedPose>> = _detectedObjects.asStateFlow()

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    private val _imageProxyFps = MutableStateFlow(0f)
    val imageProxyFps: StateFlow<Float> = _imageProxyFps.asStateFlow()
    private val _previewViewFps = MutableStateFlow(0f)
    val previewViewFps: StateFlow<Float> = _previewViewFps.asStateFlow()

    private val _inferenceFps = MutableStateFlow(0f)
    val inferenceFps: StateFlow<Float> = _inferenceFps.asStateFlow()

    private val _selectedAxis = MutableStateFlow<Axis?>(null)
    val selectedAxis: StateFlow<Axis?> = _selectedAxis.asStateFlow()
    private val _selectedDirection = MutableStateFlow<JogDirection?>(null)
    val selectedDirection: StateFlow<JogDirection?> = _selectedDirection.asStateFlow()
    private val _configuration = MutableStateFlow(
        InferenceConfiguration(
            runtime = RuntimeOption.TFLITE,
            delegate = DelegateOption.CPU,
            precision = PrecisionOption.FP32
        )
    )

    val configuration: StateFlow<InferenceConfiguration> =
        _configuration.asStateFlow()


    init {
        observeGestures()
    }

    fun startCameraSession(configuration: InferenceConfiguration) {
        _configuration.value = configuration
        viewModelScope.launch(Dispatchers.IO) {
            repository.also {
                it.initialize(configuration)
            }
        }
    }


    private var blController   : BluetoothController? = null
    fun receiveBluetoothController(
        controller: BluetoothController
    ) {
        blController = controller
    }
    //decide whether the robot is at its last moveable position.
    // incoming from esp
    var canJog = false
    var lastCommand :String? = null
    var lastSendOk: Boolean = true
    var angles : List<Float> = RobotModel.HOME_DEG


    private fun sendCommand(command: String) {
        viewModelScope.launch {
            lastSendOk = blController?.send(command)?:false
            lastCommand = command
        }
    }

    // The running jog loop. At most one at a time.
    private var jogJob: Job? = null

    // When a frame last showed TURN_LEFT/TURN_RIGHT. Set in processFrame().
    @Volatile private var lastJogGestureAt = 0L

    fun startJog(direction: JogDirection) {
        val axis = _selectedAxis.value ?: return
        val command = JogProtocol.build(axis, direction)
        val delta = if (direction == JogDirection.RIGHT) DRAWING_DEG_PER_REPEAT
        else -DRAWING_DEG_PER_REPEAT
        val previous = jogJob
        jogJob = viewModelScope.launch {
            // Let the old loop finish and send its STOP before we start.
            previous?.cancelAndJoin()
            try {
                // Keep-alive: re-send while recent frames still show the gesture.
                while (isActive &&
                    System.currentTimeMillis() - lastJogGestureAt < GESTURE_TIMEOUT_MS
                ) {
                    lastSendOk = blController?.send(command) ?: false
                    lastCommand = command
                    if (lastSendOk) angles = RobotModel.jog(angles, axis, delta)
                    delay(JogProtocol.REPEAT_MS)
                }
            } finally {
                // Runs on every exit: timeout, stopJog(), or the ViewModel closing.
                withContext(NonCancellable) {
                    blController?.send(JogProtocol.STOP)
                    lastCommand = JogProtocol.STOP
                }
            }
        }
    }

    //any changes in command stop JOG
    fun stopJog() {
        jogJob?.cancel()
    }

    var isNeutral = false
    private fun observeGestures() {
        viewModelScope.launch {
            _command
                //.distinctUntilChanged()
                .collect { cmd ->
                    stopJog()
                    if(/*cmd==RobotCommand.NONE ||*/ cmd==RobotCommand.NO_PERSON){
                        isNeutral = false
                        _selectedAxis.value = null
                        _selectedDirection.value = null
                    }

                    if(cmd==RobotCommand.NONE ){
                        _selectedDirection.value = null
                    }

                    if(cmd==RobotCommand.NEUTRAL){
                        isNeutral = true
                        _selectedAxis.value = null
                        _selectedDirection.value = null
                    }


                    if(isNeutral && cmd==RobotCommand.STOP){
                        isNeutral = false
                        _selectedAxis.value = Axis.AXIS_1
                        _selectedDirection.value = null
                    }

                    if(isNeutral && cmd==RobotCommand.START){
                        isNeutral = false
                        _selectedAxis.value = Axis.AXIS_2
                        _selectedDirection.value = null
                    }

                    if(isNeutral && cmd==RobotCommand.FORWARD){
                        isNeutral = false
                        _selectedAxis.value = Axis.AXIS_3
                        _selectedDirection.value = null
                    }

                    if(isNeutral && cmd==RobotCommand.BACKWARD){
                        isNeutral = false
                        _selectedAxis.value = Axis.AXIS_4
                        _selectedDirection.value = null
                    }

                    if(cmd==RobotCommand.TURN_RIGHT && _selectedAxis.value!=null){
                        _selectedDirection.value = JogDirection.RIGHT
                        //sendCommand(JogProtocol.build(selectedAxis.value!!, JogDirection.RIGHT))
                        startJog(JogDirection.RIGHT)
                    }

                    if(cmd==RobotCommand.TURN_LEFT && _selectedAxis.value!=null){
                        _selectedDirection.value = JogDirection.LEFT
                        //sendCommand(JogProtocol.build(selectedAxis.value!!, JogDirection.LEFT))
                        startJog(JogDirection.LEFT)
                    }
                }
        }
    }

    fun  updateImageProxyFPS(){
        imageProxyFpsCounter.tick("Analyzer FPS")
        _imageProxyFps.value = imageProxyFpsCounter.fps.toFloat()
    }

    fun processFrame(bitmap: Bitmap,revMapping: ReverseMapping) {
        if (_isProcessing.value) return
        _isProcessing.value = true

        viewModelScope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            try {
                //val results = repository.detectObjects(bitmap)
                val currentConfiguration = configuration.value
                val results = repository.detectPose(bitmap,revMapping,currentConfiguration)


                // take first person only
                val pose = results.firstOrNull()?.keyPoints?.toPose()
                //Log.d(UiDebugTag, "processFrame: $pose")
                val cmd = if (pose != null) gestureDetector.detect(pose) else RobotCommand.NO_PERSON

                // Each frame that still shows the jog gesture keeps the jog alive.
                if (cmd == RobotCommand.TURN_LEFT || cmd == RobotCommand.TURN_RIGHT) {
                    lastJogGestureAt = System.currentTimeMillis()
                }

                Log.d(UiDebugTag, "viewmodel: processFrame: $results")
                withContext(Dispatchers.Main) {
                    _detectedObjects.value = results
                    _command.value = cmd
                    updateInferenceFPS()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _isProcessing.value = false
            }

            Log.d(PerformanceDebugTag, "viewModel: processFrame: time to inference: ${System.currentTimeMillis()-now}")
        }
    }



    fun viewModelFPSRateLimit(){
        if (_isProcessing.value) return

        _isProcessing.value = true
        Log.d(PerformanceDebugTag, "viewModelFPSRateLimit")
        updateInferenceFPS()

        viewModelScope.launch {
            delay(1000) // performance delay
            _isProcessing.value = false
        }
    }


    fun updateInferenceFPS() {
         inferenceFpsCounter.tick("Inference FPS")
        _inferenceFps.value = inferenceFpsCounter.fps.toFloat()
    }

    override fun onCleared() {
        super.onCleared()
    }

    fun updateDisplayFps(frameCount: Int) {
        _previewViewFps.value = frameCount.toFloat()
    }
}
