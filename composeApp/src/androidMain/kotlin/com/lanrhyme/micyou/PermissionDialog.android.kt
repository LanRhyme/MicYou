package com.lanrhyme.micyou

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

/**
 * 设置项容器组件 (与 DesktopSettings.kt 中保持一致)
 */
@Composable
private fun SettingsItemContainer(
    modifier: Modifier = Modifier,
    cardOpacity: Float = 1f,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = cardOpacity * 0.5f))
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
    ) {
        content()
    }
}

/**
 * 获取所有需要检查的权限列表 (Android implementation)
 */
fun getRequiredPermissions(activity: ComponentActivity): List<PermissionState> {
    val permissions = mutableListOf<PermissionState>()

    // 录音权限 - 必选，所有版本都需要
    permissions.add(
        PermissionState(
            type = PermissionType.RECORD_AUDIO,
            manifestPermission = Manifest.permission.RECORD_AUDIO,
            isGranted = ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
            minSdkVersion = 0
        )
    )

    // 蓝牙连接权限 - Android 12+ (API 31+)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        permissions.add(
            PermissionState(
                type = PermissionType.BLUETOOTH_CONNECT,
                manifestPermission = Manifest.permission.BLUETOOTH_CONNECT,
                isGranted = ContextCompat.checkSelfPermission(activity, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED,
                minSdkVersion = Build.VERSION_CODES.S
            )
        )
        permissions.add(
            PermissionState(
                type = PermissionType.BLUETOOTH_SCAN,
                manifestPermission = Manifest.permission.BLUETOOTH_SCAN,
                isGranted = ContextCompat.checkSelfPermission(activity, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED,
                minSdkVersion = Build.VERSION_CODES.S
            )
        )
    }

    // 通知权限 - Android 13+ (API 33+)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        permissions.add(
            PermissionState(
                type = PermissionType.POST_NOTIFICATIONS,
                manifestPermission = Manifest.permission.POST_NOTIFICATIONS,
                isGranted = ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
                minSdkVersion = Build.VERSION_CODES.TIRAMISU
            )
        )
    }

    return permissions
}

/**
 * 检查是否所有必选权限都已授予 (Android actual implementation)
 */
actual fun hasAllRequiredPermissions(permissions: List<PermissionState>): Boolean {
    return permissions.filter { it.type.isRequired }.all { it.isGranted }
}

/**
 * Android 权限管理设置部分 (actual implementation)
 */
@Composable
actual fun AndroidPermissionManagementSection(cardOpacity: Float) {
    var showPermissionDialog by remember { mutableStateOf(false) }
    val permissionStrings = LocalPermissionStrings.current
    val activity = LocalContext.current as? ComponentActivity

    // Use rememberLauncherForActivityResult to handle permission requests
    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // Permissions result handled - the dialog will refresh permissions on next render
    }

    var permissions by remember { mutableStateOf<List<PermissionState>>(emptyList()) }

    // Refresh permissions whenever showPermissionDialog changes to true
    LaunchedEffect(showPermissionDialog) {
        if (showPermissionDialog && activity != null) {
            permissions = getRequiredPermissions(activity)
        }
    }

    // Initial load
    LaunchedEffect(Unit) {
        if (activity != null) {
            permissions = getRequiredPermissions(activity)
        }
    }

    val grantedCount = permissions.count { it.isGranted }
    val totalCount = permissions.size
    val hasAllRequired = hasAllRequiredPermissions(permissions)

    // Use same style as other settings items - headline and supporting text only
    SettingsItemContainer(
        cardOpacity = cardOpacity,
        onClick = { showPermissionDialog = true }
    ) {
        ListItem(
            headlineContent = {
                Text(permissionStrings.permissionManagementLabel)
            },
            supportingContent = {
                Text(
                    text = if (totalCount == 0) {
                        permissionStrings.permissionChecking
                    } else if (hasAllRequired) {
                        permissionStrings.permissionAllGrantedStatus.format(grantedCount, totalCount)
                    } else {
                        permissionStrings.permissionMissingWarning.format(grantedCount, totalCount)
                    }
                )
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
        )
    }

    if (showPermissionDialog && activity != null) {
        PermissionDialog(
            permissions = permissions,
            onDismiss = { showPermissionDialog = false },
            onRequestPermissions = { perms ->
                permissionLauncher.launch(perms.toTypedArray())
            }
        )
    }
}

/**
 * 权限对话框组件 (Android actual implementation)
 */
@Composable
actual fun PermissionDialog(
    permissions: List<PermissionState>,
    onDismiss: () -> Unit,
    onRequestPermissions: (List<String>) -> Unit
) {
    val strings = LocalPermissionStrings.current
    val activity = LocalContext.current as? ComponentActivity
    var currentPermissions by remember { mutableStateOf(permissions) }

    // Use rememberLauncherForActivityResult to handle permission requests within the dialog
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // Refresh permissions after request completes
        if (activity != null) {
            currentPermissions = getRequiredPermissions(activity)
        }
    }

    // 更新权限状态
    LaunchedEffect(Unit) {
        if (activity != null) {
            currentPermissions = getRequiredPermissions(activity)
        }
    }

    val hasRequiredPermissions = hasAllRequiredPermissions(currentPermissions)
    val ungrantedPermissions = currentPermissions.filter { !it.isGranted }

    AlertDialog(
        onDismissRequest = {
            // 只有当所有必选权限都已授予时才能关闭对话框
            if (hasRequiredPermissions) {
                onDismiss()
            }
        },
        title = { Text(strings.permissionDialogTitle) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = strings.permissionDialogMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(8.dp))

                // 权限列表
                currentPermissions.forEach { permission ->
                    PermissionCard(
                        permission = permission,
                        strings = strings,
                        onRequestPermission = { perm ->
                            permissionLauncher.launch(arrayOf(perm))
                        }
                    )
                }
            }
        },
        confirmButton = {
            if (hasRequiredPermissions) {
                TextButton(onClick = onDismiss) {
                    Text(strings.permissionAllGranted)
                }
            } else {
                Button(onClick = {
                    val toRequest = ungrantedPermissions.map { it.manifestPermission }
                    if (toRequest.isNotEmpty()) {
                        permissionLauncher.launch(toRequest.toTypedArray())
                    }
                }) {
                    Text(strings.permissionRequestButton)
                }
            }
        }
    )
}

/**
 * 权限卡片组件 - 类似新手引导的步骤卡片风格
 */
@Composable
private fun PermissionCard(
    permission: PermissionState,
    strings: PermissionStrings,
    onRequestPermission: (String) -> Unit
) {
    val icon: ImageVector = when (permission.type) {
        PermissionType.RECORD_AUDIO -> Icons.Default.Mic
        PermissionType.BLUETOOTH_CONNECT -> Icons.Default.Bluetooth
        PermissionType.BLUETOOTH_SCAN -> Icons.Default.Bluetooth
        PermissionType.POST_NOTIFICATIONS -> Icons.Default.Notifications
    }

    val label = when (permission.type) {
        PermissionType.RECORD_AUDIO -> strings.permissionRecordAudioLabel
        PermissionType.BLUETOOTH_CONNECT -> strings.permissionBluetoothConnectLabel
        PermissionType.BLUETOOTH_SCAN -> strings.permissionBluetoothScanLabel
        PermissionType.POST_NOTIFICATIONS -> strings.permissionPostNotificationsLabel
    }

    val description = when (permission.type) {
        PermissionType.RECORD_AUDIO -> strings.permissionRecordAudioDesc
        PermissionType.BLUETOOTH_CONNECT -> strings.permissionBluetoothConnectDesc
        PermissionType.BLUETOOTH_SCAN -> strings.permissionBluetoothScanDesc
        PermissionType.POST_NOTIFICATIONS -> strings.permissionPostNotificationsDesc
    }

    val canRequest = !permission.isGranted

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (canRequest) {
                    Modifier.clickable { onRequestPermission(permission.manifestPermission) }
                } else {
                    Modifier
                }
            ),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (permission.isGranted) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.width(8.dp))
                // 必选/可选标记
                Text(
                    text = if (permission.type.isRequired) {
                        strings.permissionRequired
                    } else {
                        strings.permissionOptional
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (permission.type.isRequired) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                // 已授权标记
                if (permission.isGranted) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}