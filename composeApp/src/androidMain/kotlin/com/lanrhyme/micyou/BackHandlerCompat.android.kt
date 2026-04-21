package com.lanrhyme.micyou

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect

@Composable
actual fun BackHandlerCompat(enabled: Boolean, onBack: () -> Unit) {
    val currentOnBack = rememberUpdatedState(onBack)

    PredictiveBackHandler(enabled = enabled) { progress ->
        try {
            // Consume progress updates; invoke back only when gesture is committed.
            progress.collect {}
            currentOnBack.value()
        } catch (_: CancellationException) {
            // Gesture cancelled by user; keep current UI state.
        }
    }
}
