package com.lanrhyme.micyou

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 开源库信息
 */
data class OpenSourceLibrary(
    val name: String,
    val license: String
)

/**
 * 开源库列表 - 单一数据源
 */
val OpenSourceLibraries = listOf(
    OpenSourceLibrary("JetBrains Compose Multiplatform", "Apache License 2.0"),
    OpenSourceLibrary("Kotlin Coroutines", "Apache License 2.0"),
    OpenSourceLibrary("Ktor", "Apache License 2.0"),
    OpenSourceLibrary("Material 3 Components", "Apache License 2.0"),
    OpenSourceLibrary("MaterialKolor", "MIT License"),
    OpenSourceLibrary("ONNX Runtime", "MIT License"),
    OpenSourceLibrary("JTransforms", "BSD 3-Clause License"),
    OpenSourceLibrary("composeNativeTray", "MIT License"),
    OpenSourceLibrary("FileKit", "MIT License"),
    OpenSourceLibrary("kotlinx-datetime", "Apache License 2.0"),
    OpenSourceLibrary("kotlinx-serialization", "Apache License 2.0")
)

/**
 * 开源库列表组件 - 可在桌面端和移动端复用
 */
@Composable
fun OpenSourceLibrariesList(
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(OpenSourceLibraries.size) { index ->
            val library = OpenSourceLibraries[index]
            Text(library.name, style = MaterialTheme.typography.titleSmall)
            Text(library.license, style = MaterialTheme.typography.bodySmall)
        }
    }
}