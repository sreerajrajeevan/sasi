package com.sree.sasi.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColorScheme = lightColorScheme(
    primary = SasiPrimary,
    onPrimary = SasiOnPrimary,
    secondary = SasiSecondary,
    tertiary = SasiTertiary,
    background = SasiBackground,
    onBackground = SasiOnBackground,
    surface = SasiSurface,
    onSurface = SasiOnSurface,
)

@Composable
fun SasiTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColorScheme,
        typography = Typography,
        content = content,
    )
}
