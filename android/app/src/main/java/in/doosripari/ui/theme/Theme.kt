package `in`.doosripari.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf

val LocalDoosriColors = staticCompositionLocalOf { DoosriLightColors }

/**
 * The app's MaterialTheme. Two things are deliberate:
 *
 * 1. It does not set `dynamicColor`. Plan section 4.2 asks for one consistent
 *    look across handsets, and a wallpaper-derived palette cannot be checked
 *    against the contrast rules in 4.3. An app for this audience should look
 *    the same on every phone.
 * 2. It maps the plan's tokens onto Material's scheme rather than replacing it.
 *    Material still paints things for us — ripples, disabled states, text
 *    selection handles — and a default scheme would clash with the tokens.
 */
@Composable
fun DoosriPariTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DoosriDarkColors else DoosriLightColors

    val scheme = if (darkTheme) {
        darkColorScheme(
            primary = colors.accent,
            onPrimary = colors.background,
            secondary = colors.marigold,
            onSecondary = colors.background,
            error = colors.safety,
            onError = colors.background,
            background = colors.background,
            onBackground = colors.ink,
            surface = colors.surface,
            onSurface = colors.ink,
            surfaceVariant = colors.background,
            onSurfaceVariant = colors.inkSecondary,
            outline = colors.divider,
        )
    } else {
        lightColorScheme(
            primary = colors.accent,
            onPrimary = colors.background,
            secondary = colors.marigold,
            onSecondary = colors.background,
            error = colors.safety,
            onError = colors.background,
            background = colors.background,
            onBackground = colors.ink,
            surface = colors.surface,
            onSurface = colors.ink,
            surfaceVariant = colors.background,
            onSurfaceVariant = colors.inkSecondary,
            outline = colors.divider,
        )
    }

    CompositionLocalProvider(LocalDoosriColors provides colors) {
        MaterialTheme(
            colorScheme = scheme,
            typography = DoosriTypography,
            content = content,
        )
    }
}

/** Shorthand: `DoosriTheme.colors.safety`. */
object DoosriTheme {
    val colors: DoosriColors
        @Composable get() = LocalDoosriColors.current
}
