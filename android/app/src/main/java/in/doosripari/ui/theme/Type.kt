package `in`.doosripari.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp

/**
 * Plan section 4.3, the type scale, transcribed exactly.
 *
 * The sizes are set in sp, so they scale with the system font-size setting —
 * which matters more here than usual, because the plan's users are 60+ and many
 * will already have raised the system size. Nothing in the app should
 * override that; there is no fixed-size text in the app.
 *
 * Every weight is Regular, Semibold or Bold. The plan forbids light and thin
 * outright: at 20pt on a cheap LCD in daylight, thin strokes disappear.
 */
private val Sans = FontFamily.SansSerif

private val TrimBoth = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None,
)

private fun style(
    size: Int,
    weight: FontWeight,
    lineHeight: Int = (size * 1.4f).toInt(),
) = TextStyle(
    fontFamily = Sans,
    fontSize = size.sp,
    fontWeight = weight,
    lineHeight = lineHeight.sp,
    lineHeightStyle = TrimBoth,
)

/**
 * Noto Sans, per the plan. `FontFamily.SansSerif` is Android's system sans,
 * which is Roboto for Latin and falls back to the Noto Sans variant for each
 * script — Noto Sans Devanagari for Hindi and Marathi, Noto Sans Tamil, and so
 * on. That fallback is automatic and is why the Indic scripts render at all.
 *
 * The alternative is bundling the Noto files as `res/font` and naming the family
 * explicitly, which would pin the version and stop it tracking the OS's
 * security and legibility fixes. Worth doing only if a device is found that
 * falls back badly — see plan section 8.4 on testing Devanagari, Tamil and
 * Bengali at every size.
 */
val DoosriTypography = Typography(
    // Large title — "Namaste, Sharma ji".
    displayLarge = style(36, FontWeight.Bold),

    // Section titles.
    headlineLarge = style(28, FontWeight.Bold),

    // Card titles.
    headlineMedium = style(22, FontWeight.SemiBold),

    // All reading text. This is the size the plan means when it says the body
    // should be legible at arm's length on a 5-inch screen.
    bodyLarge = style(20, FontWeight.Normal),

    // Secondary lines and button labels.
    bodyMedium = style(18, FontWeight.Normal),

    // The smallest text we use. Nothing below this, ever.
    bodySmall = style(16, FontWeight.Normal),

    // Material 3 requires these six to be filled; they are part of components
    // this app does not build yet, but the defaults are far too small and too
    // light for this audience, so they are sized to the plan's scale rather
    // than left alone.
    displayMedium = style(32, FontWeight.Bold),
    displaySmall = style(30, FontWeight.SemiBold),
    headlineSmall = style(20, FontWeight.SemiBold),
    titleLarge = style(22, FontWeight.SemiBold),
    titleMedium = style(18, FontWeight.SemiBold),
    titleSmall = style(16, FontWeight.SemiBold),
    labelLarge = style(20, FontWeight.SemiBold),
    labelMedium = style(18, FontWeight.Medium),
    labelSmall = style(16, FontWeight.Medium),
)
