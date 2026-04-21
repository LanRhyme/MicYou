package com.lanrhyme.micyou

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

@Composable
actual fun MobileSettingsNavigation(
    homeContent: @Composable (onOpenSettings: () -> Unit) -> Unit,
    settingsContent: @Composable (onClose: () -> Unit) -> Unit
) {
    var settingsVisible by remember { mutableStateOf(false) }

    homeContent {
        settingsVisible = true
    }

    if (settingsVisible) {
        settingsContent {
            settingsVisible = false
        }
    }
}
