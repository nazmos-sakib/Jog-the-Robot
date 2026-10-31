package dev.naz.jogtherobot.utils

import android.util.Log

class FpsCounter {
    private var lastTime = System.nanoTime()
    private var frameCount = 0

    var fps = 0.0

    fun tick(tag: String = "FPS") {
        frameCount++
        val now = System.nanoTime()
        val elapsed = (now - lastTime) / 1_000_000_000.0

        if (elapsed >= 1.0) {
            fps = (frameCount / elapsed)
            Log.d(tag, "$tag : %.2f".format(fps))

            frameCount = 0
            lastTime = now
        }
    }
}