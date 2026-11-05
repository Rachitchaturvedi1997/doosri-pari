package `in`.doosripari.ui.theme

import androidx.compose.ui.unit.dp

/**
 * Plan section 4.3, spacing and shape. Named rather than raw so that a control's
 * size is a decision, not a number somebody typed.
 */
object DoosriDimens {
    /** 4, 8, 12, 16, 24, 32, 48. */
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
    val huge = 48.dp

    /** Plan section 4.3: screen side margins are 20. */
    val screenMargin = 20.dp

    /** Controls are at least 56 tall, with 12 between them. */
    val minControlHeight = 56.dp
    val controlGap = 12.dp

    /** Main action buttons are 64 tall and full width. */
    val primaryButtonHeight = 64.dp

    /** The check-in button is 96 tall — plan section 4.4. */
    val checkInButtonHeight = 96.dp

    /** Cards are 14, large buttons are 32. */
    val cardRadius = 14.dp
    val buttonRadius = 32.dp

    /** The mic button floats above the tab bar at 64 round. */
    val micButtonSize = 64.dp
}
