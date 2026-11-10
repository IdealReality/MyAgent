package me.rerere.myagent.ui.context

import androidx.compose.runtime.compositionLocalOf
import me.rerere.myagent.ui.hooks.CustomTtsState

val LocalTTSState = compositionLocalOf<CustomTtsState> { error("Not provided yet") }
