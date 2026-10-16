package com.assassins377.remoteplayer.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.assassins377.remoteplayer.settings.SettingsStore
import com.assassins377.remoteplayer.settings.ThemeMode

@Composable
fun RemotePlayerTheme(dynamicColor: Boolean = true, content: @Composable () -> Unit) {
    val s by SettingsStore.settings.collectAsStateWithLifecycle()
    val darkTheme = when (s.theme) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }
    var colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        darkTheme -> darkColorScheme()
        else -> lightColorScheme()
    }
    if (darkTheme && s.amoled) {
        colors = colors.copy(background = Color.Black, surface = Color.Black, surfaceContainer = Color.Black,
            surfaceContainerLow = Color.Black, surfaceContainerLowest = Color.Black)
    }
    MaterialTheme(colorScheme = colors, content = content)
}
