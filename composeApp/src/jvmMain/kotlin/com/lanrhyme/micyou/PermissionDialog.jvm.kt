package com.lanrhyme.micyou

import androidx.compose.runtime.Composable

/**
 * Desktop 平台不需要权限管理，提供空实现
 */
@Composable
actual fun AndroidPermissionManagementSection(cardOpacity: Float) {
    // No implementation needed for Desktop platform
}

/**
 * Desktop 平台权限检查 - 总是返回 true
 */
actual fun hasAllRequiredPermissions(permissions: List<PermissionState>): Boolean {
    return true // Desktop doesn't need runtime permissions
}

/**
 * Desktop 平台权限对话框 - 空实现
 */
@Composable
actual fun PermissionDialog(
    permissions: List<PermissionState>,
    onDismiss: () -> Unit,
    onRequestPermissions: (List<String>) -> Unit
) {
    // No implementation needed for Desktop platform
    // This should never be called on Desktop
}