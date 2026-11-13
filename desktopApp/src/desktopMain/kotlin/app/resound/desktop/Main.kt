package app.resound.desktop

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.resound.ui.App

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "涛声",
        state = rememberWindowState(size = DpSize(640.dp, 420.dp)),
    ) {
        App()
    }
}