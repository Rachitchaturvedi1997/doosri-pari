package `in`.doosripari.ui.design

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow

/**
 * Plan section 4.3's type scale, as six named composables.
 *
 * The point of this object is that a size becomes a decision. If a screen
 * author wants smaller text they have to pick one of six sizes and say why,
 * rather than type `13.sp` at the call site and leave the next person to copy
 * it. There are exactly six sizes in this app, and the smallest is 16sp —
 * nothing below it exists anywhere, because the plan's users read at arm's
 * length on a cheap screen in daylight, and 14sp is not visible to them.
 *
 * Every style is read from [MaterialTheme.typography] rather than built here.
 * That is what makes dark mode work for free — the theme already carries the
 * right weights for each colour scheme — and it keeps the whole app on one
 * scale instead of six lookalike scales invented per screen.
 *
 * All six are set in sp, so they follow the system font-size setting. Nothing
 * here sets a fixed height or a default line cap: [maxLines] defaults to null
 * so that a 200% system font wraps rather than clips, which is plan section
 * 6.16 and the single most important thing in this file. A 75-year-old who has
 * raised their phone's font size to 200% must still be able to read a Sahayak
 * answer, not see its bottom half sheared off.
 *
 * Weights are Regular, Semibold or Bold. Light and thin appear nowhere in this
 * app; at 20sp on a budget LCD in daylight, thin strokes simply disappear.
 */
object DoosriText {

    /**
     * 36sp Bold — the screen title. "Namaste, Sharma ji."
     *
     * One per screen, at the top. Deliberately not a name in a styles list:
     * a screen title is a thing, and it is a thing that appears once.
     */
    @Composable
    fun largeTitle(
        text: String,
        modifier: Modifier = Modifier,
        color: Color = Color.Unspecified,
        textAlign: TextAlign? = null,
        maxLines: Int? = null,
        overflow: TextOverflow = TextOverflow.Ellipsis,
    ) = DoosriTextNode(
        text = text,
        style = MaterialTheme.typography.displayLarge,
        modifier = modifier,
        color = color,
        textAlign = textAlign,
        maxLines = maxLines,
        overflow = overflow,
    )

    /**
     * 28sp Bold — a section title, the thing that heads a run of cards or rows.
     *
     * Bigger than the card titles it introduces, on purpose. Hierarchy here is
     * carried by size, not by colour, because a section heading that is only
     * greyed-down or a shade different does not survive a bright room.
     */
    @Composable
    fun title(
        text: String,
        modifier: Modifier = Modifier,
        color: Color = Color.Unspecified,
        textAlign: TextAlign? = null,
        maxLines: Int? = null,
        overflow: TextOverflow = TextOverflow.Ellipsis,
    ) = DoosriTextNode(
        text = text,
        style = MaterialTheme.typography.headlineLarge,
        modifier = modifier,
        color = color,
        textAlign = textAlign,
        maxLines = maxLines,
        overflow = overflow,
    )

    /**
     * 22sp Semibold — a card title.
     *
     * The one weight between Regular and Bold. It is a card's single heading,
     * and it must be readable at a glance in a long scroll without competing
     * with the screen title above it.
     */
    @Composable
    fun headline(
        text: String,
        modifier: Modifier = Modifier,
        color: Color = Color.Unspecified,
        textAlign: TextAlign? = null,
        maxLines: Int? = null,
        overflow: TextOverflow = TextOverflow.Ellipsis,
    ) = DoosriTextNode(
        text = text,
        style = MaterialTheme.typography.headlineMedium,
        modifier = modifier,
        color = color,
        textAlign = textAlign,
        maxLines = maxLines,
        overflow = overflow,
    )

    /**
     * 20sp Regular, 1.4x line height — all reading text.
     *
     * This is the size the plan means when it says body text should be legible
     * at arm's length on a five-inch screen: one step above the platform
     * default, and still scaling with the system setting on top of that.
     *
     * The 1.4 line height is not decoration. Devanagari and Tamil stack
     * matras and pulli above and below the line box, and at a tighter leading
     * a Hindi paragraph clips the tops of its own letters. `bodyLarge` in
     * `ui/theme/Type.kt` already carries it; the call is made here so no screen
     * has to remember to.
     */
    @Composable
    fun body(
        text: String,
        modifier: Modifier = Modifier,
        color: Color = Color.Unspecified,
        textAlign: TextAlign? = null,
        maxLines: Int? = null,
        overflow: TextOverflow = TextOverflow.Ellipsis,
    ) = DoosriTextNode(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        modifier = modifier,
        color = color,
        textAlign = textAlign,
        maxLines = maxLines,
        overflow = overflow,
    )

    /**
     * 18sp Regular — the secondary line under a heading.
     *
     * For a supporting detail, never for a heading and never for the only text
     * on a card. If a sentence is the thing the user came to read, it is
     * [body]; this size is for the line that qualifies it.
     */
    @Composable
    fun callout(
        text: String,
        modifier: Modifier = Modifier,
        color: Color = Color.Unspecified,
        textAlign: TextAlign? = null,
        maxLines: Int? = null,
        overflow: TextOverflow = TextOverflow.Ellipsis,
    ) = DoosriTextNode(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        modifier = modifier,
        color = color,
        textAlign = textAlign,
        maxLines = maxLines,
        overflow = overflow,
    )

    /**
     * 16sp Regular — the smallest text in the app.
     *
     * Legal to use, and the floor. It is here for the floating mic's word and
     * for a timestamp, where anything larger would crowd the control it
     * belongs to. It is not a licence to add a footnote: if a line needs to be
     * 16sp, it needs to be short enough that 18sp would have fit.
     */
    @Composable
    fun caption(
        text: String,
        modifier: Modifier = Modifier,
        color: Color = Color.Unspecified,
        textAlign: TextAlign? = null,
        maxLines: Int? = null,
        overflow: TextOverflow = TextOverflow.Ellipsis,
    ) = DoosriTextNode(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        modifier = modifier,
        color = color,
        textAlign = textAlign,
        maxLines = maxLines,
        overflow = overflow,
    )
}

/**
 * The one place a [Text] is built.
 *
 * It exists so that the six functions above cannot drift apart: no screen gets
 * a different `softWrap` or a different default cap, and there is exactly one
 * line in the app to change if the scale is ever revised.
 *
 * `color` defaults to [Color.Unspecified] rather than to the palette's `ink`,
 * and that is deliberate. An unspecified colour lets `Text` inherit the
 * container's content colour, which is what makes a label inside a primary
 * button come out light on the accent fill. Hard-coding `ink` here would put
 * near-black text on a dark teal button and the bug would be invisible in code
 * review.
 */
@Composable
private fun DoosriTextNode(
    text: String,
    style: TextStyle,
    modifier: Modifier,
    color: Color,
    textAlign: TextAlign?,
    maxLines: Int?,
    overflow: TextOverflow,
) {
    Text(
        text = text,
        modifier = modifier,
        style = style,
        color = color,
        textAlign = textAlign,
        maxLines = maxLines,
        overflow = overflow,
        softWrap = true,
    )
}
