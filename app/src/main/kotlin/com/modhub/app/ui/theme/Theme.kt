package com.modhub.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = Green40, onPrimary = Neutral99, primaryContainer = Green90, onPrimaryContainer = Green10,
    secondary = Wood40, onSecondary = Neutral99, secondaryContainer = Wood90, onSecondaryContainer = Wood10,
    tertiary = Sky40, onTertiary = Neutral99, tertiaryContainer = Sky90, onTertiaryContainer = Sky10,
    error = Red40, onError = Neutral99, errorContainer = Red90, onErrorContainer = Red10,
    background = Neutral99, onBackground = Neutral10, surface = Neutral99, onSurface = Neutral10,
    surfaceVariant = NeutralVariant90, onSurfaceVariant = NeutralVariant30, outline = NeutralVariant50,
)

private val DarkColors = darkColorScheme(
    primary = Green80, onPrimary = Green20, primaryContainer = Green30, onPrimaryContainer = Green90,
    secondary = Wood80, onSecondary = Wood20, secondaryContainer = Wood30, onSecondaryContainer = Wood90,
    tertiary = Sky80, onTertiary = Sky10, tertiaryContainer = Sky40, onTertiaryContainer = Sky90,
    error = Red80, onError = Red10, errorContainer = Red40, onErrorContainer = Red90,
    background = Neutral10, onBackground = Neutral90, surface = Neutral10, onSurface = Neutral90,
    surfaceVariant = NeutralVariant30, onSurfaceVariant = NeutralVariant80, outline = NeutralVariant50,
)

@Composable
fun ModHubTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    /** Material You colours on Android 12+; below that, the farm-green/wood brand scheme. */
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, typography = ModHubTypography, content = content)
}
