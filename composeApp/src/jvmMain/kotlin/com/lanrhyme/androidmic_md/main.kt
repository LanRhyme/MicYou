package com.lanrhyme.androidmic_md

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "AndroidMicMd",
    ) {
        App()
    }
}
