/*
 * MicYou — Turns your Android device into a high-quality PC microphone.
 * Copyright (C) 2026 LanRhyme <https://github.com/LanRhyme/MicYou>
 *
 * Haze bridge — normal mode: delegates to dev.chrisbanes.haze library.
 */

package com.lanrhyme.micyou.ui.compose.haze

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.chrisbanes.haze.HazeState as RealHazeState
import dev.chrisbanes.haze.HazeStyle as RealHazeStyle
import dev.chrisbanes.haze.HazeTint as RealHazeTint
import dev.chrisbanes.haze.haze as realHaze
import dev.chrisbanes.haze.hazeChild as realHazeChild
import dev.chrisbanes.haze.hazeEffect as realHazeEffect
import dev.chrisbanes.haze.hazeSource as realHazeSource
import dev.chrisbanes.haze.rememberHazeState as realRememberHazeState

typealias HazeState = RealHazeState
typealias HazeStyle = RealHazeStyle
typealias HazeTint = RealHazeTint

fun Modifier.hazeEffect(state: HazeState, style: HazeStyle): Modifier =
    this.then(Modifier.realHazeEffect(state = state, style = style))

fun Modifier.hazeSource(state: HazeState): Modifier =
    this.then(Modifier.realHazeSource(state = state))

fun Modifier.haze(state: HazeState): Modifier =
    this.then(Modifier.realHaze(state = state))

fun Modifier.hazeChild(state: HazeState, style: HazeStyle): Modifier =
    this.then(Modifier.realHazeChild(state = state, style = style))

@Composable
fun rememberHazeState(): HazeState = realRememberHazeState()
