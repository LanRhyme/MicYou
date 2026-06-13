package com.lanrhyme.micyou

import androidx.compose.runtime.Composable
import androidx.activity.compose.BackHandler

@Composable
fun BackHandlerCompat(enabled: Boolean, onBack: () -> Unit) {
    BackHandler(enabled = enabled) {
        onBack()
    }
}
