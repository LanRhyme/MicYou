/*
 * MicYou — Turns your Android device into a high-quality PC microphone.
 * Copyright (C) 2026 LanRhyme <https://github.com/LanRhyme/MicYou>
 *
 * Material3 ColorScheme compatibility extensions.
 * These extension properties provide fallbacks for color roles added in newer Material3 versions.
 * When the member property exists (newer Material3), it takes precedence over the extension.
 */

package com.lanrhyme.micyou.ui.compose.material3

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color

// ——— Surface variants ———

val ColorScheme.surfaceBright: Color
    get() = surface

val ColorScheme.surfaceDim: Color
    get() = surface

// ——— Primary Fixed ———

val ColorScheme.primaryFixed: Color
    get() = primary

val ColorScheme.primaryFixedDim: Color
    get() = primary

val ColorScheme.onPrimaryFixed: Color
    get() = onPrimary

val ColorScheme.onPrimaryFixedVariant: Color
    get() = onPrimaryContainer

// ——— Secondary Fixed ———

val ColorScheme.secondaryFixed: Color
    get() = secondary

val ColorScheme.secondaryFixedDim: Color
    get() = secondary

val ColorScheme.onSecondaryFixed: Color
    get() = onSecondary

val ColorScheme.onSecondaryFixedVariant: Color
    get() = onSecondaryContainer

// ——— Tertiary Fixed ———

val ColorScheme.tertiaryFixed: Color
    get() = tertiary

val ColorScheme.tertiaryFixedDim: Color
    get() = tertiary

val ColorScheme.onTertiaryFixed: Color
    get() = onTertiary

val ColorScheme.onTertiaryFixedVariant: Color
    get() = onTertiaryContainer
