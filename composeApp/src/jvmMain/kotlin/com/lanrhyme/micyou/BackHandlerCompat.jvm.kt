package com.lanrhyme.micyou

import androidx.compose.runtime.Composable

@Composable
actual fun BackHandlerCompat(enabled: Boolean, onBack: () -> Unit) {
    // No-op on JVM/desktop targets; back handling can be implemented separately if needed
}
