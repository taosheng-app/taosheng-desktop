package com.taosheng.desktop

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Taosheng Desktop",
        state = rememberWindowState(size = DpSize(640.dp, 420.dp)),
    ) {
        App()
    }
}