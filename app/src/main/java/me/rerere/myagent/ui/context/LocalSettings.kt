package me.rerere.myagent.ui.context

import androidx.compose.runtime.staticCompositionLocalOf
import me.rerere.myagent.data.datastore.Settings

val LocalSettings = staticCompositionLocalOf<Settings> {
    error("No SettingsStore provided")
}
