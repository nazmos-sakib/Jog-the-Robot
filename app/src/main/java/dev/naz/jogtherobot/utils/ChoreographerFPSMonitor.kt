package dev.naz.jogtherobot.utils

import android.view.Choreographer
import dev.naz.jogtherobot.ui.viewmodel.CameraViewModel

object ChoreographerFPSMonitor {
    private var frameCallback: Choreographer.FrameCallback? = null

      fun startFpsMonitor(viewModel: CameraViewModel) {
        var lastTime = System.nanoTime()
        var frameCount = 0

        frameCallback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                frameCount++

                if (frameTimeNanos - lastTime >= 1_000_000_000) {
                    viewModel.updateDisplayFps(frameCount)
                    frameCount = 0
                    lastTime = frameTimeNanos
                }

                Choreographer.getInstance().postFrameCallback(this)
            }
        }

        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

      fun stopFpsMonitor() {
        frameCallback?.let {
            Choreographer.getInstance().removeFrameCallback(it)
        }
    }
}