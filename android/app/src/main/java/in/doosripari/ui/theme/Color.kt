package `in`.doosripari.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Plan section 4.3, transcribed exactly. These are not Moholla's palette —
 * Moholla's is sunflower lime and violet, which suits a younger audience and
 * fails the contrast rules here. Do not "harmonise" these with Moholla's.
 *
 * The plan sets two hard constraints on this palette:
 *   * `ink` and `inkSecondary` must hold at least 7:1 against `background`
 *     in both columns. That is AAA, chosen deliberately because the users are
 *     60+ and many will be reading in poor daylight.
 *   * `safety` and `ok` are semantic. Never used for decoration.
 */
data class DoosriColors(
    val background: Color,
    val surface: Color,
    val ink: Color,
    val inkSecondary: Color,
    val accent: Color,
    val marigold: Color,
    val safety: Color,
    val ok: Color,
    val divider: Color,
)

val DoosriLightColors = DoosriColors(
    background = Color(0xFFFAFAF7),
    surface = Color(0xFFFFFFFF),
    ink = Color(0xFF13201F),
    inkSecondary = Color(0xFF3F4E4C),
    accent = Color(0xFF0B6E66),
    marigold = Color(0xFFB97700),
    safety = Color(0xFFC0311B),
    ok = Color(0xFF1E7A34),
    divider = Color(0xFFE1E7E5),
)

val DoosriDarkColors = DoosriColors(
    background = Color(0xFF0F1414),
    surface = Color(0xFF1A2121),
    ink = Color(0xFFEEF3F2),
    inkSecondary = Color(0xFFB8C6C4),
    accent = Color(0xFF6FD3C9),
    marigold = Color(0xFFF2B544),
    safety = Color(0xFFFF8A73),
    ok = Color(0xFF7BD38B),
    divider = Color(0xFF2A3332),
)
