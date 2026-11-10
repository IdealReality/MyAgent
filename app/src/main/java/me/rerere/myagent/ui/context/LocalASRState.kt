package me.rerere.myagent.ui.context

import androidx.compose.runtime.compositionLocalOf
import me.rerere.myagent.ui.hooks.CustomAsrState

val LocalASRState = compositionLocalOf<CustomAsrState> { error("Not provided yet") }

