package dev.naz.jogtherobot

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.naz.jogtherobot.ui.JogTheRobotApp
import dev.naz.jogtherobot.ui.theme.JogTheRobotTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val controller = (application as JogApp).bluetoothController
        setContent {
            JogTheRobotTheme {
                JogTheRobotApp(controller = controller)
            }
        }
    }
}
