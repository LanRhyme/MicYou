package com.lanrhyme.micyou

import kotlinx.coroutines.CoroutineScope

data class BackgroundSettings(
    val imagePath: String = "",
    val brightness: Float = 0.5f,
    val blurRadius: Float = 0f,
    val cardOpacity: Float = 1f,
    val enableHazeEffect: Boolean = false
) {
    val hasCustomBackground: Boolean
        get() = imagePath.isNotEmpty()
}

expect object BackgroundImagePicker {
    fun pickImage(scope: CoroutineScope, onResult: (String?) -> Unit)
}
