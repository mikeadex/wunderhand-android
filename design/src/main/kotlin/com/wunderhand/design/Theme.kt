package com.wunderhand.design

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

/**
 * Material 3, wearing Wunderhand's clothes rather than its own.
 *
 * The scheme is fixed — no dynamic colour from the wallpaper, and light only
 * in v1, as the web and the iPhone are. Material's components (a text field's
 * cursor, a progress ring, a sheet) pick the palette up from here; the app's
 * own components read [WHColors] and [WHType] directly.
 */
private val Scheme = lightColorScheme(
    primary = WHColors.Accent,
    onPrimary = WHColors.Surface,
    primaryContainer = WHColors.Accent100,
    onPrimaryContainer = WHColors.Accent800,
    secondary = WHColors.Ink,
    onSecondary = WHColors.Surface,
    background = WHColors.Bg,
    onBackground = WHColors.Ink,
    surface = WHColors.Surface,
    onSurface = WHColors.Ink,
    surfaceVariant = WHColors.Well,
    onSurfaceVariant = WHColors.Neutral700,
    outline = WHColors.Neutral500,
    outlineVariant = WHColors.Neutral300,
    error = WHColors.Accent,
    onError = WHColors.Surface,
    errorContainer = WHColors.Accent100,
    onErrorContainer = WHColors.Accent800,
)

private val WHShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),   // tag
    small = RoundedCornerShape(8.dp),        // segmented
    medium = RoundedCornerShape(12.dp),      // card, button, field
    large = RoundedCornerShape(12.dp),
    extraLarge = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp), // sheet
)

private val WHTypography = Typography().run {
    // Anything Material draws for itself is Archivo too.
    copy(
        displayLarge = displayLarge.copy(fontFamily = Archivo), displayMedium = displayMedium.copy(fontFamily = Archivo),
        displaySmall = displaySmall.copy(fontFamily = Archivo), headlineLarge = headlineLarge.copy(fontFamily = Archivo),
        headlineMedium = headlineMedium.copy(fontFamily = Archivo), headlineSmall = headlineSmall.copy(fontFamily = Archivo),
        titleLarge = titleLarge.copy(fontFamily = Archivo), titleMedium = titleMedium.copy(fontFamily = Archivo),
        titleSmall = titleSmall.copy(fontFamily = Archivo), bodyLarge = WHType.FieldValue, bodyMedium = WHType.Body,
        bodySmall = WHType.Meta, labelLarge = WHType.Button, labelMedium = labelMedium.copy(fontFamily = Archivo),
        labelSmall = labelSmall.copy(fontFamily = Archivo),
    )
}

@Composable
fun WunderhandTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, shapes = WHShapes, typography = WHTypography, content = content)
}
