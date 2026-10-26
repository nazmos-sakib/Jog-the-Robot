package dev.naz.jogtherobot

import android.app.Application
import dev.naz.jogtherobot.bluetooth.BluetoothController

/**
 * Holds the single [BluetoothController] for the whole app process so the
 * Bluetooth connection (and its broadcast receiver) survives screen rotation
 * and navigation between screens.
 */
class JogApp : Application() {

    lateinit var bluetoothController: BluetoothController
        private set

    override fun onCreate() {
        super.onCreate()
        bluetoothController = BluetoothController(this)
    }
}
