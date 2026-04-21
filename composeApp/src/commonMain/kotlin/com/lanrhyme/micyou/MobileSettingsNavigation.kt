package com.lanrhyme.micyou

import androidx.compose.runtime.Composable

@Composable
expect fun MobileSettingsNavigation(
    homeContent: @Composable (onOpenSettings: () -> Unit) -> Unit,
    settingsContent: @Composable (onClose: () -> Unit) -> Unit
)
