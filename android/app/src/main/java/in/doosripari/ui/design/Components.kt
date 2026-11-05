package `in`.doosripari.ui.design

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import `in`.doosripari.R
import `in`.doosripari.ui.theme.DoosriDimens
import `in`.doosripari.ui.theme.DoosriTheme

/**
 * Plan section 4.4, the core components, and the accessibility rules of
 * section 6.16 that attach to them.
 *
 * Section 6.16 is not a nice-to-have here. This app exists for people who are
 * 60 and over, many of whom read large print, many of whom have a hand that
 * shakes, and all of whom will meet this UI in the worst possible state — a
 * cheap phone, a bright room, a bad day. So the rules below are load-bearing
 * in a way they would not be in a typical app:
 *
 *  * Every control is at least 56dp on its short side, with 12dp between
 *    controls. 48dp is the platform minimum and it is not enough for a finger
 *    that is not steady.
 *  * Every control is at least [DoosriDimens.minControlHeight] *tall* but never
 *    a fixed height. `heightIn(min = …)` is used throughout rather than
 *    `height(…)` on purpose: at a 200% system font a fixed height shears the
 *    label in half, and a 200% font is a setting these users are genuinely
 *    likely to have turned on. Nothing in this file caps a line count by
 *    default for the same reason — text wraps, then the control grows.
 *  * Every icon has a text label beside it. There is no icon-only control in
 *    this file, and that is a rule rather than a style preference: an
 *    unfamiliar glyph is not a name, and this app's icons are chosen from a
 *    Material set that includes symbols these users have never seen.
 *  * Content descriptions are written for a screen reader, not for a
 *    developer. Where a label is already visible, the icon's description is
 *    null and the whole control is one node — TalkBack reading "chevron
 *    right" after the title it just read is noise, and noise costs a
 *    screen-reader user their place.
 *  * No state is carried by colour alone. `ok` green and `safety` red are the
 *    one pair a colour-blind user cannot rely on, and they are reserved for
 *    SOS, a missed check-in and "I'm fine" — so every place either appears,
 *    a word or an icon carries the same information. See
 *    [DoosriCheckInButton].
 *
 * Semantic colours are used nowhere else. `safety` and `ok` are not
 * decoration; a fourth red button would spend the one colour that means "you
 * are in danger".
 */

/**
 * English the plan names but `strings.xml` does not carry yet.
 *
 * The app ships in ten languages and section 3.3 says every string is
 * translated by a person. The design system is the last place still holding
 * English inline, because the components it builds are the ones the screens
 * wrap, and because inventing resource names here would collide with whoever
 * writes the real translations. Each of these needs a `strings.xml` entry and
 * a `stringResource` call before the app ships in a second language — the
 * existing `feature/checkin/CheckInScreen.kt` carries the same flag on its
 * inline strings.
 */
private object PendingStrings {
    /** Sheet dismissal. Must be a verb: "Cancel", "Not now", "Keep". */
    const val CANCEL = "Cancel"

    /** The floating voice button. "Bolo" means "speak" — the plan's own word. */
    const val BOLO = "Bolo"

    /** Shown under the mic while it is hearing the user. */
    const val LISTENING = "Listening"

    /** Announced by the mic's state, not drawn. */
    const val LISTENING_STATE = "Listening now"
    const val IDLE_STATE = "Not listening"
}

/**
 * The system font scale, or 1 when the platform will not say.
 *
 * Exposed because plan section 6.16 treats the Android font scale as something
 * the design system reacts to, not something it merely survives. Text always
 * scales on its own — it is set in sp — so this is only needed where a
 * *layout* has to change, which today means [DoosriListRow] dropping its value
 * under the title. Reading it in one place also means a screen can assert on it
 * in a test instead of guessing what Compose did.
 */
val DoosriFontScale: Float
    @Composable get() = LocalAccessibilityManager.current?.fontScale ?: 1f

/** Icon size inside a control. The plan puts a Material Symbol beside every label. */
private val IconSize = 28.dp

/** The tick on a completed check-in, and the microphone. */
private val EmphasisIconSize = 40.dp

/**
 * A screen: a title, actions, and a scrolling body with the plan's margins.
 *
 * This is the chrome every screen shares, and the reason screens do not each
 * invent a toolbar. Two things are deliberate:
 *
 *  * The title is a 36sp large title, and the same text carries
 *    `heading()` semantics so TalkBack announces "heading" — a screen-reader
 *    user navigates this app by jumping between headings, and that only works
 *    if the title is marked as one.
 *  * The body is a plain scrolling [Column], not a lazy list. The content slot
 *    is a `ColumnScope`, so it is a column; putting a `LazyColumn` inside a
 *    vertically scrolling column throws at runtime. A screen whose body is a
 *    long feed should scroll the page rather than compose every row, and that
 *    is the trade this signature makes: fewer decisions at each call site, at
 *    the cost of unbounded list length. Add a paging component rather than
 *    reaching for `LazyColumn` here.
 *
 * The back arrow is not drawn here. It belongs to the navigation host, and
 * section 3.4 requires it to stay visible on every screen — the host, not
 * this component, is where that promise is kept.
 *
 * The 20dp side margins come from [DoosriDimens.screenMargin] and are applied
 * to the toolbar as well as the body, so the title lines up with the cards
 * beneath it. They are not repeated at each call site because a 24dp margin on
 * one screen and a 16dp on the next reads as a mistake, and looks like one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DoosriScreen(
    title: String,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = DoosriTheme.colors

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                modifier = Modifier.padding(horizontal = DoosriDimens.screenMargin),
                title = {
                    DoosriText.largeTitle(
                        text = title,
                        modifier = Modifier.semantics { heading() },
                        color = colors.ink,
                    )
                },
                // SOS goes in here, and nowhere else:
                // `actions = { DoosriSosButton(onClick = …) }`.
                actions = actions,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = colors.background,
                    titleContentColor = colors.ink,
                    actionIconContentColor = colors.accent,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(
                    start = DoosriDimens.screenMargin,
                    end = DoosriDimens.screenMargin,
                    top = DoosriDimens.lg,
                    // Breathing room past the parent's padding, so a screen's
                    // last control is never flush against the tab bar.
                    bottom = DoosriDimens.huge,
                ),
            // 16 between siblings. A screen that wraps its own children in a
            // Column opts out of this, which is the only way to get a tighter
            // or looser rhythm on purpose.
            verticalArrangement = Arrangement.spacedBy(DoosriDimens.lg),
            content = content,
        )
    }
}

/**
 * Plan section 4.4: surface colour, 14dp radius, 20dp padding, one title, at
 * most three lines of body, one action.
 *
 * The card is a unit of content, and the limits above are what keeps it one. A
 * card with two titles has not decided what it is; a card with six lines is a
 * screen that was filed in the wrong place; a card with two actions is a
 * screen that has not chosen its primary. Those three are the rules a designer
 * breaks first and a user pays for first, so they are stated here rather than
 * left to judgement.
 *
 * The expected shape of [content] is therefore:
 *
 * ```
 * DoosriCard {
 *     DoosriText.headline("Pension")            // the one title
 *     DoosriText.body("Credits on the 1st …")   // at most three lines
 *     DoosriSecondaryButton("Open")             // the one action
 * }
 * ```
 *
 * When [onClick] is given the whole card becomes the tap target, so a pensioner
 * with a tremor does not have to hit a 30dp link inside it. When it is null the
 * card is not a control at all and announces nothing.
 *
 * The 1dp outline is not in the plan's description of a card, and it earns its
 * place anyway: `surface` is #FFFFFF on a #FAFAF7 background, a difference of
 * about two percent, which is invisible to a user with reduced contrast
 * sensitivity. The outline gives the card an edge without introducing a second
 * tone, and `divider` is the neutral token for exactly that.
 */
@Composable
fun DoosriCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = DoosriTheme.colors
    val shape = RoundedCornerShape(DoosriDimens.cardRadius)
    val border = BorderStroke(1.dp, colors.divider)

    if (onClick != null) {
        // A tappable card is a control, so it obeys the 56dp floor even when
        // its content is shorter than that.
        Surface(
            onClick = onClick,
            modifier = modifier
                .fillMaxWidth()
                .heightIn(min = DoosriDimens.minControlHeight),
            shape = shape,
            color = colors.surface,
            contentColor = colors.ink,
            border = border,
        ) {
            CardBody(content)
        }
    } else {
        Surface(
            modifier = modifier.fillMaxWidth(),
            shape = shape,
            color = colors.surface,
            contentColor = colors.ink,
            border = border,
        ) {
            CardBody(content)
        }
    }
}

/** The 20dp interior of a card, in both its tappable and static forms. */
@Composable
private fun CardBody(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(DoosriDimens.screenMargin),
        verticalArrangement = Arrangement.spacedBy(DoosriDimens.sm),
        content = content,
    )
}

/**
 * Plan section 4.4: full width, 64dp tall, accent fill, 20sp semibold label,
 * icon on the left.
 *
 * **One per screen.** This is the rule most worth defending in review. Two
 * full-width accent buttons on one screen do not read as "one of these is more
 * important" — they read as "this app wants two things from me", and the user
 * picks neither. A screen with two of them has not decided what it is for;
 * the second should be [DoosriSecondaryButton], or the screen should be split.
 * The same button must not appear twice on one screen, including once in a
 * sheet opened from it.
 *
 * Full width because the target is the whole button, not the words in it: at
 * this size the label is roughly a fifth of the button's width, and a
 * five-word Hindi or Tamil label is narrower still. Every one of this app's ten
 * languages is longer than English.
 *
 * The label is `labelLarge`, 20sp Semibold — section 4.4 names the weight
 * rather than leaving it at regular, because a button label is the one piece of
 * text a user is looking for and should not need to hunt for.
 *
 * The height is a minimum, not a height. See the file KDoc: at a 200% system
 * font a fixed 64dp cuts a Marathi label in half, and half a label is worse
 * than no app.
 */
@Composable
fun DoosriPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val colors = DoosriTheme.colors

    Button(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = DoosriDimens.primaryButtonHeight),
        enabled = enabled,
        shape = RoundedCornerShape(DoosriDimens.buttonRadius),
        colors = ButtonDefaults.buttonColors(
            containerColor = colors.accent,
            contentColor = colors.background,
            // A disabled primary is the accent, washed out — not grey. Grey
            // reads as a broken system control; a faded accent reads as "this
            // one is not available yet", which is what it means.
            disabledContainerColor = colors.accent.copy(alpha = 0.20f),
            disabledContentColor = colors.ink.copy(alpha = 0.70f),
        ),
        contentPadding = PaddingValues(horizontal = DoosriDimens.xl),
    ) {
        DoosriButtonLabel(label = text, icon = icon)
    }
}

/**
 * Plan section 4.4: 56dp tall, outlined, label always visible.
 *
 * Everything here except the action is a demotion of the primary button: it is
 * shorter, it is an outline rather than a fill, and its label is
 * [DoosriText.callout] rather than semibold. The plan puts "button labels" at
 * 18 regular in section 4.3 and reserves the 20sp semibold for the main action,
 * and the difference in weight is also what separates the two controls
 * visually when they sit side by side.
 *
 * The outline is 2dp rather than Material's 1dp. A one-pixel border on a
 * light background is effectively invisible to a user with reduced contrast
 * sensitivity, and then the button is an invisible control.
 */
@Composable
fun DoosriSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val colors = DoosriTheme.colors

    OutlinedButton(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = DoosriDimens.minControlHeight),
        enabled = enabled,
        shape = RoundedCornerShape(DoosriDimens.buttonRadius),
        border = BorderStroke(2.dp, colors.accent),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = colors.accent,
        ),
        contentPadding = PaddingValues(horizontal = DoosriDimens.xl),
    ) {
        DoosriButtonLabel(label = text, icon = icon)
    }
}

/**
 * Icon on the left, then the label — section 4.4.
 *
 * The label is given `weight(1f, fill = false)` so it may take the remaining
 * width and wrap rather than overflow. This is what lets a button survive a
 * 200% font: the text reflows, and the button's `heightIn` grows around it.
 *
 * The icon's content description is null. The label beside it *is* the name,
 * and a description as well would make TalkBack read the same thing twice —
 * worse, it would read the icon's generic name in the middle of the action
 * the user was already reading about.
 */
@Composable
private fun DoosriButtonLabel(label: String, icon: ImageVector?) {
    val colors = DoosriTheme.colors

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DoosriDimens.sm),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(IconSize),
            )
        }
        DoosriText.body(
            text = label,
            modifier = Modifier.weight(1f, fill = false),
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Plan section 4.4: at least 64dp tall, icon in a tinted rounded square, title,
 * optional value, chevron, whole row tappable.
 *
 * The whole row is the target, not the title text. A row's title is a few
 * words in the middle of a 360dp screen, and a pensioner aiming at it will
 * miss; a row that fills the width cannot be missed.
 *
 * The icon sits in a tinted rounded square rather than floating on the
 * background. The tint is the accent at 12% — deliberately *not* `ok` and not
 * `safety`, because a red or green square on a settings list is decoration
 * pretending to be meaning, and a colour-blind user would read it as one.
 *
 * The row is a single semantics node. A screen-reader user hears
 * "Life certificate, due 1 November" once, not "Life certificate, calendar
 * icon, due 1 November, chevron right" in four stops. The icon and the
 * chevron carry no descriptions of their own for the same reason.
 *
 * The [value] is supplementary, and at a 200% system font it is what gives
 * way: it drops beneath the title rather than squeezing it. The title is what
 * the user came to read; the value is context.
 *
 * The chevron appears only when [onClick] is non-null. A row with a chevron
 * promises somewhere to go, and a dead promise is worse than no chevron.
 */
@Composable
fun DoosriListRow(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    value: String? = null,
    onClick: (() -> Unit)? = null,
) {
    val colors = DoosriTheme.colors
    val description = if (value == null) title else "$title, $value"
    val stacked = DoosriFontScale >= 1.5f

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        // "double tap to <title>" is what TalkBack offers, and
                        // naming the target is what makes that usable.
                        onClickLabel = title,
                        role = Role.Button,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                }
            )
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(vertical = DoosriDimens.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DoosriDimens.md),
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(DoosriDimens.cardRadius))
                .background(colors.accent.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(IconSize),
            )
        }

        DoosriText.body(
            text = title,
            modifier = Modifier.weight(1f),
            color = colors.ink,
        )

        if (value != null && !stacked) {
            DoosriText.callout(text = value, color = colors.inkSecondary)
        }

        if (onClick != null) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = colors.inkSecondary,
                modifier = Modifier.size(IconSize),
            )
        }
    }

    if (value != null && stacked) {
        DoosriText.callout(
            text = value,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 60.dp, end = DoosriDimens.md, bottom = DoosriDimens.sm),
            color = colors.inkSecondary,
        )
    }
}

/**
 * Plan section 4.4: 96dp, green, "Main theek hoon · I'm fine". Once pressed it
 * shows the time with a tick.
 *
 * The largest control in the app by a wide margin, and that is the point. This
 * is the one thing a user has to find and press correctly every morning, and a
 * target that is easy to miss means a check-in that is never recorded, which
 * means a family is called for nothing. It fills the width rather than being a
 * fixed square so the label fits in Hindi, Marathi and Tamil without
 * truncating — the English string is the shortest of the ten.
 *
 * **`ok` green is one of three things and three things only** — SOS, a missed
 * check-in, and "I'm fine". It is never used for decoration, and it is not
 * this component's colour to spend: if the whole app were tinted green the
 * button would stop meaning "I have told my family I am all right" and start
 * meaning "this is a button". Where a screen wants a success tint, it wants a
 * different token.
 *
 * **The state is never carried by that green alone.** A colour-blind user
 * cannot rely on the one pair this app uses for good and bad, so a completed
 * check-in also changes shape — a tick appears — and changes words — the label
 * becomes the time. Green, tick and "Checked in at 8:12 am" all say the same
 * thing, so losing any one of them loses nothing.
 *
 * The whole control is one semantics node carrying both an action and a state:
 * `contentDescription` is what the control *does* — "Tell everyone you are fine
 * today" — and `stateDescription` says whether today is done. Announcing only
 * the label would leave a screen-reader user unable to tell whether they had
 * already checked in this morning, which is exactly the question they are
 * asking.
 *
 * A completed day is not clickable. Checking in twice is not a thing, and a
 * second press that silently does nothing reads as a broken app.
 */
@Composable
fun DoosriCheckInButton(
    checkedAtLabel: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = DoosriTheme.colors
    val done = checkedAtLabel != null

    val action = stringResource(R.string.check_in_button_description)
    val pending = stringResource(R.string.check_in_button)
    val doneLabel = if (checkedAtLabel != null) {
        stringResource(R.string.check_in_done, checkedAtLabel)
    } else {
        null
    }

    Button(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = DoosriDimens.checkInButtonHeight)
            .semantics {
                contentDescription = action
                stateDescription = doneLabel ?: pending
            },
        enabled = enabled && !done,
        shape = RoundedCornerShape(DoosriDimens.buttonRadius),
        colors = ButtonDefaults.buttonColors(
            containerColor = colors.ok,
            contentColor = colors.background,
            // A finished day keeps the green. It is a good day, not a disabled
            // control, and fading it out would read as something having gone
            // wrong. The tick and the changed words carry the state instead.
            disabledContainerColor = colors.ok,
            disabledContentColor = colors.background,
        ),
        contentPadding = PaddingValues(horizontal = DoosriDimens.lg),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DoosriDimens.md),
        ) {
            if (done) {
                // The tick is the non-colour half of the state.
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = null,
                    modifier = Modifier.size(EmphasisIconSize),
                )
            }
            DoosriText.body(
                text = doneLabel ?: pending,
                modifier = Modifier.weight(1f, fill = false),
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * Plan section 4.4: in the top toolbar, red, and labelled "SOS" — never an
 * icon alone.
 *
 * The word is the whole point. This is the control that has to be understood
 * by a 75-year-old, at three in the morning, in the dark, by a family member
 * who has never opened the app before and is frightened. There is no icon that
 * reads as "send help to my family" to somebody who has not seen one, and
 * there is no time to learn one. Three letters do not need a legend.
 *
 * It belongs in the top toolbar of every main screen, in this form:
 *
 * ```
 * DoosriScreen(title = "…", actions = { DoosriSosButton(onClick = onSos) }) { … }
 * ```
 *
 * It is the only control in the app allowed to use `safety` red, and it stays
 * red whether or not it is the thing the user is currently looking at. A
 * button that changes colour depending on the screen is a button whose meaning
 * depends on context.
 *
 * The content description is the action, not the glyph: "SOS. Emergency help."
 * A screen reader announcing the icon would announce a glyph whose name means
 * nothing to the user hearing it.
 *
 * The press-and-hold of section 6.3 is not in this component. The signature is
 * a plain click, and the hold belongs to the SOS screen that this opens — that
 * screen shows the five-second countdown with a large Cancel, and it is that
 * countdown, not this button, which is the confirmation the plan requires. A
 * two-second hold here would also make the control the one control in the app
 * that a trembling hand activates *by accident*.
 */
@Composable
fun DoosriSosButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DoosriTheme.colors
    val label = stringResource(R.string.sos_button)
    val description = stringResource(R.string.sos_button_description)

    Button(
        onClick = onClick,
        modifier = modifier
            .heightIn(min = DoosriDimens.minControlHeight)
            // Stated rather than inherited: a toolbar button sized only by its
            // label is 72dp wide, and the 56dp floor is a promise this component
            // makes rather than one Material happens to keep.
            .defaultMinSize(
                minWidth = 88.dp,
                minHeight = DoosriDimens.minControlHeight,
            ),
        shape = RoundedCornerShape(DoosriDimens.buttonRadius),
        colors = ButtonDefaults.buttonColors(
            containerColor = colors.safety,
            contentColor = colors.background,
        ),
        contentPadding = PaddingValues(horizontal = DoosriDimens.lg),
    ) {
        DoosriText.body(
            text = label,
            modifier = Modifier.semantics {
                contentDescription = "$label. $description"
            },
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Plan section 4.4: one line saying what goes here, one button to add the first
 * item.
 *
 * The message is the whole point. An empty screen that says "Nothing here" has
 * failed the user twice: it has not told them what *would* be here, and it has
 * given them no way to put the first thing there. "Your medicines will appear
 * here" plus one button answers both.
 *
 * One line, not three. A user staring at an empty list is already unsure
 * whether they have done something wrong; a paragraph of reassurance makes
 * them more so.
 *
 * The button is a [DoosriSecondaryButton], not the screen's primary. An empty
 * state is usually one card among several, and it is the screen above it that
 * owns the one primary action. If a screen is *only* an empty state, put a
 * [DoosriPrimaryButton] above it and leave this one out — two full-width
 * buttons saying the same thing is a worse version of the same idea.
 */
@Composable
fun DoosriEmptyState(
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val colors = DoosriTheme.colors

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = DoosriDimens.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(DoosriDimens.xl),
    ) {
        DoosriText.body(
            text = message,
            color = colors.inkSecondary,
            textAlign = TextAlign.Center,
        )

        if (actionLabel != null && onAction != null) {
            DoosriSecondaryButton(
                text = actionLabel,
                onClick = onAction,
            )
        }
    }
}

/**
 * Plan section 4.4: a sheet for short tasks, with a visible grabber, a Cancel
 * button as well as swipe-down, opening at medium height.
 *
 * The Cancel button is not a convenience. Swipe-to-dismiss is a gesture, and
 * section 3.4 rules out swipe-only actions for an audience that will not
 * reliably discover or perform one — and section 4.1 rules out quick and
 * multi-finger gestures outright. A sheet must be leaveable by tapping
 * something with a word on it, so the word is here and it is 56dp tall.
 *
 * The sheet opens half-height first (`skipPartiallyExpanded` is left at its
 * default, false), which is the plan's "medium height first". A full-height
 * sheet on a short task makes the task look bigger than it is, and hides the
 * screen behind it that the user may want to see while deciding.
 *
 * The grabber is for the hand, not for the screen reader: it is decorative
 * rather than focusable, because a screen-reader user has Cancel and dragging
 * is not something a switch-control user can perform. Offering a focusable
 * handle they cannot operate would be worse than offering none.
 *
 * The body scrolls, because at medium height a sheet with a long form in it
 * would otherwise put its last field under the user's thumb.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DoosriSheet(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = DoosriTheme.colors
    // Default is skipPartiallyExpanded = false, which is the half-height
    // opening the plan asks for. Setting it to true here would be a regression
    // to a full sheet on every short task.
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        sheetState = sheetState,
        containerColor = colors.surface,
        contentColor = colors.ink,
        dragHandle = {
            // A wider, taller grabber than Material's. A 32x4dp handle is a
            // hairline on a 5-inch screen, and this one is the only visible
            // signal that the thing can be dragged at all.
            Box(
                modifier = Modifier
                    .size(width = 48.dp, height = 5.dp)
                    .clip(CircleShape)
                    .background(colors.inkSecondary),
            )
        },
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = DoosriDimens.screenMargin),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(DoosriDimens.md),
            ) {
                DoosriText.headline(
                    text = title,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { heading() },
                    color = colors.ink,
                )
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.heightIn(min = DoosriDimens.minControlHeight),
                ) {
                    DoosriText.callout(text = PendingStrings.CANCEL, color = colors.accent)
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = DoosriDimens.screenMargin),
                color = colors.divider,
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(
                        horizontal = DoosriDimens.screenMargin,
                        vertical = DoosriDimens.lg,
                    ),
                content = content,
            )
        }
    }
}

/**
 * Plan section 4.4: an alert, only for irreversible actions, with buttons that
 * are verbs.
 *
 * "Delete this document?" and buttons reading "Delete document" and "Keep".
 * Never "OK" and "Cancel" alone. "OK" is the word for agreeing to something
 * you have not been told what it does; a person who cannot read the question
 * confidently will tap the habitual button, which is the wrong one. "Keep" says
 * the same thing as "Cancel" and also says what will *not* happen — and on a
 * dialog about a document, "Keep" is the button a frightened user should be
 * able to reach without understanding the question at all. A destructive
 * action in this app must be the easier of the two to refuse.
 *
 * This is the only place in the app a modal is used, and the two parameters
 * that name the buttons are the enforcement: there is no default and no
 * overload that fills them in for you. If a screen wants a dialog, it has to
 * write out both verbs, and the writer is the person who notices the action
 * is reversible and does not need one.
 *
 * **The confirm button is not red.** `safety` is reserved for SOS, a missed
 * check-in and "I'm fine" — the things where red means somebody is in danger.
 * Making "Delete document" red as well spends that, and the next time a real
 * emergency appears the user has already learned to see red as a button colour.
 * The verb is what carries the warning here, and the dialog is modal, so there
 * is nothing behind it to act on by accident.
 *
 * On Android the dismiss button sits on the left and the confirm on the right,
 * which is Material's order and matches the muscle memory of every other
 * Android app this user's phone has ever shown them.
 */
@Composable
fun DoosriConfirmAlert(
    title: String,
    message: String,
    confirmLabel: String,
    dismissLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = DoosriTheme.colors

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(DoosriDimens.cardRadius),
        containerColor = colors.surface,
        titleContentColor = colors.ink,
        textContentColor = colors.inkSecondary,
        title = {
            DoosriText.headline(
                text = title,
                modifier = Modifier.semantics { heading() },
                color = colors.ink,
            )
        },
        text = {
            DoosriText.body(text = message, color = colors.inkSecondary)
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.heightIn(min = DoosriDimens.minControlHeight),
            ) {
                DoosriText.callout(
                    text = confirmLabel,
                    modifier = Modifier.semantics { contentDescription = confirmLabel },
                    color = colors.accent,
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = DoosriDimens.minControlHeight),
            ) {
                DoosriText.callout(
                    text = dismissLabel,
                    modifier = Modifier.semantics { contentDescription = dismissLabel },
                    color = colors.inkSecondary,
                )
            }
        },
    )
}

/**
 * Plan section 4.4: the floating "Bolo" mic — a round button, 64dp, above the
 * tab bar, floating over the content.
 *
 * **Tonal surface, not glass.** Glass is an iOS 26 material; on Android the
 * equivalent is a tonal (tinted) surface, and the tint here is the accent at
 * 14%. A translucent surface on Android would sit over content whose contrast
 * cannot be guaranteed, and this app's text is required to hold 7:1 — a
 * material that changes what is behind it cannot make that promise.
 *
 * It floats, so it carries a shadow; it overlaps content, so it is placed by
 * the caller above the navigation bar rather than inside the page flow. The
 * screen underneath is still responsible for leaving room.
 *
 * It is not a tab. The plan puts the mic and SOS outside the tab bar
 * deliberately: tabs navigate, and a control that acts is not navigation.
 *
 * **The word is under the button, not inside it.** A 64dp circle cannot hold
 * both a glyph and "Bolo" at 16sp, and an icon-only round control is exactly
 * what section 4.3 forbids. The label is part of the same tap target and merges
 * into the same semantics node, so TalkBack says "Bolo, not listening" rather
 * than reading a microphone.
 *
 * **[listening] changes shape, not just colour.** A second glyph replaces the
 * microphone and the word changes from "Bolo" to "Listening". The tint also
 * deepens, which is decoration — but the glyph and the word are what carry it,
 * so a user who cannot tell the two tints apart knows which state they are in.
 */
@Composable
fun DoosriFloatingMicButton(
    onClick: () -> Unit,
    listening: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = DoosriTheme.colors
    val label = if (listening) PendingStrings.LISTENING else PendingStrings.BOLO

    Column(
        modifier = modifier
            .semantics(mergeDescendants = true) {
                contentDescription = PendingStrings.BOLO
                stateDescription = if (listening) {
                    PendingStrings.LISTENING_STATE
                } else {
                    PendingStrings.IDLE_STATE
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(DoosriDimens.micButtonSize)
                .shadow(elevation = 6.dp, shape = CircleShape)
                .clip(CircleShape)
                .background(
                    if (listening) {
                        colors.accent.copy(alpha = 0.24f)
                    } else {
                        colors.accent.copy(alpha = 0.14f)
                    }
                )
                .clickable(role = Role.Button, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (listening) {
                    Icons.Filled.GraphicEq
                } else {
                    Icons.Filled.Mic
                },
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(EmphasisIconSize),
            )
        }
        DoosriText.caption(
            text = label,
            modifier = Modifier.padding(top = DoosriDimens.xs),
            color = colors.ink,
        )
    }
}

/**
 * A section title in a scrolling screen: "Pension", "Today's practice".
 *
 * Marked as a heading, so a screen-reader user can jump between sections of a
 * long Today screen instead of reading it line by line. On a screen with
 * twenty items in it, that is the difference between reading a page and
 * reading a fortnight.
 *
 * `title` and not `headline`: section 4.3 makes 28sp Bold the section-title
 * size and 22sp Semibold the card-title size, and inverting that would make
 * every heading on the screen smaller than the cards under it.
 *
 * The [modifier] is the caller's, applied outside this component's own layout,
 * so a screen that needs to pad or place a section heading (inside a sheet, say)
 * passes that here rather than wrapping the header in a second layout.
 */
@Composable
fun DoosriSectionHeader(
    text: String,
    modifier: Modifier = Modifier,
) {
    DoosriText.title(
        text = text,
        modifier = modifier
            .fillMaxWidth()
            .padding(top = DoosriDimens.xl)
            .semantics { heading() },
        color = DoosriTheme.colors.ink,
    )
}
