package music.ai.recommend

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import kotlin.system.exitProcess

fun main() {
    // Before anything else has anything to say: everything printed from here on is kept beside
    // the data as well as shown, so a fault does not have to be reproduced to be read.
    ConsoleLog.beside(java.io.File(System.getProperty("user.home"), ".aimusic"))
    println("Starting Desktop App...")
    application {
        Window(
            onCloseRequest = {
                exitApplication()
                exitProcess(0)
            }, 
            title = "AiMusic"
        ) {
            println("Window created")
            App(
                onRaise = {
                    window.isMinimized = false
                    window.toFront()
                    window.requestFocus()
                }
            )
        }
    }
}
