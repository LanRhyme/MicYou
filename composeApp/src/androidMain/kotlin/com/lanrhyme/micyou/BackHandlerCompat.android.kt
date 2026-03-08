package com.lanrhyme.micyou

import androidx.compose.runtime.Composable
import androidx.activity.compose.BackHandler

@Composable
actual fun BackHandlerCompat(enabled: Boolean, onBack: () -> Unit) {
    BackHandler(enabled = enabled) {
        onBack()
    }
}
