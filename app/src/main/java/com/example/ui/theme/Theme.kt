package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val CasaSombraColorScheme = darkColorScheme(
    primary = ManorBrass,
    onPrimary = ManorObsidian,
    primaryContainer = ManorSlate,
    onPrimaryContainer = ManorParchment,
    secondary = ManorMoonlight,
    onSecondary = ManorObsidian,
    secondaryContainer = ManorCharcoal,
    onSecondaryContainer = ManorParchment,
    tertiary = ManorCrimson,
    onTertiary = ManorParchment,
    background = ManorObsidian,
    onBackground = ManorParchment,
    surface = ManorCharcoal,
    onSurface = ManorParchment,
    surfaceVariant = ManorSlate,
    onSurfaceVariant = ManorMist,
    outline = ManorBrassDim,
    error = ManorCrimsonBright,
    onError = ManorParchment
)

@Composable
fun CasaSombraTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = CasaSombraColorScheme,
        typography = CasaSombraTypography,
        content = content
    )
}
