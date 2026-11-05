package `in`.doosripari.feature.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Air
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Newspaper
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Savings
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.SelfImprovement
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material.icons.rounded.VolunteerActivism
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `in`.doosripari.data.CheckInPreferences
import `in`.doosripari.data.CheckInRepository
import `in`.doosripari.data.CheckInSnapshot
import `in`.doosripari.feature.checkin.CheckInMethod
import `in`.doosripari.feature.checkin.CheckInViewModel
import `in`.doosripari.ui.design.DoosriCard
import `in`.doosripari.ui.design.DoosriCheckInButton
import `in`.doosripari.ui.design.DoosriEmptyState
import `in`.doosripari.ui.design.DoosriListRow
import `in`.doosripari.ui.design.DoosriScreen
import `in`.doosripari.ui.design.DoosriSecondaryButton
import `in`.doosripari.ui.design.DoosriSectionHeader
import `in`.doosripari.ui.design.DoosriSheet
import `in`.doosripari.ui.design.DoosriSosButton
import `in`.doosripari.ui.theme.DoosriDimens
import `in`.doosripari.ui.theme.DoosriTheme
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Where a tap on a card goes. Navigation is a callback rather than a graph
 * because the app has no navigation dependency yet; one sealed type is easier to
 * exhaust in a test than eleven `onX` parameters, and it is the list the host has
 * to handle when it does add NavHost.
 */
sealed interface TodayDestination {
    data class Reminder(val id: String) : TodayDestination
    data class Practice(val id: String) : TodayDestination
    data class Circle(val id: String) : TodayDestination
    data class News(val id: String) : TodayDestination
    data class Room(val id: String) : TodayDestination
    data class Seva(val id: String) : TodayDestination
    data object Weather : TodayDestination
    data object ScamShield : TodayDestination
    data object Grievance : TodayDestination
    data object Sahayak : TodayDestination
    data object Toolkit : TodayDestination
    data object EditToday : TodayDestination
}

/**
 * Aaj (Today) — plan sections 4.5, 6.2 and 13.
 *
 * The screen is one scroller of cards and nothing else. Plan 3.1 gives this
 * audience one focus per screen and a big calm default, and plan 6.2's card list
 * is a feed, not a set of choices. The only control that is genuinely large is
 * the check-in button.
 *
 * Every card is [SectionCard], so loading, failure, "nothing here" and "here is
 * what we saved" are four different-looking things on every card rather than
 * four rules re-implemented per card. That matters more than it looks: a pensioner
 * who has read "From your circles" on a train and later sees it empty must be
 * able to tell the difference between *no new posts* and *the app is broken*.
 */
@Composable
fun TodayScreen(
    state: TodayUiState,
    checkIn: TodayCheckIn,
    onCheckIn: () -> Unit,
    onRetry: () -> Unit,
    onHideSection: (TodaySection, Boolean) -> Unit,
    onCreditAnswer: (Boolean) -> Unit,
    onNavigate: (TodayDestination) -> Unit,
    onSos: () -> Unit,
    onReadAloud: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DoosriTheme.colors
    var showCreditSheet by remember { mutableStateOf(false) }
    var showEditSheet by remember { mutableStateOf(false) }

    // Plan 6.2: the credit-day prompt appears on the day, and only then. Not
    // asking on any other day is the point — a pensioner is asked about their
    // pension once a month, not on every open of the app.
    //
    // A side effect in the middle of composition would write state during the
    // first pass and recompose immediately; LaunchedEffect does the same thing
    // one frame later and does not fight the composition.
    LaunchedEffect(state.creditPrompt) {
        if (state.creditPrompt is CreditPrompt.Due) {
            showCreditSheet = true
        }
    }

    DoosriScreen(
        title = "Namaste, ${state.addressAs.ifBlank { "ji" }}",
        modifier = modifier,
        actions = {
            TextButton(onClick = onReadAloud) {
                Icon(Icons.Rounded.VolumeUp, contentDescription = null, modifier = Modifier.size(24.dp))
                Text(
                    text = "Read aloud",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.accent,
                    modifier = Modifier.padding(start = DoosriDimens.xs),
                )
            }
            TextButton(onClick = { showEditSheet = true }) {
                Icon(Icons.Outlined.Tune, contentDescription = null, modifier = Modifier.size(24.dp))
                Text(
                    text = "Edit today",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.accent,
                    modifier = Modifier.padding(start = DoosriDimens.xs),
                )
            }
            DoosriSosButton(onClick = onSos, modifier = Modifier.padding(start = DoosriDimens.sm))
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(DoosriDimens.lg),
        ) {
            // ------------------------------------------------------------------
            // 1. Greeting. Plan 6.2 card 1: the date, and the tithi if the user
            // turned it on. It is the one thing that is never ranked and never
            // hidden — the plan puts it first unconditionally.
            // ------------------------------------------------------------------
            Column {
                Text(
                    text = state.dateLabel,
                    style = MaterialTheme.typography.headlineMedium,
                    color = colors.ink,
                )
                state.vratLabel?.let { vrat ->
                    Text(
                        text = vrat,
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.marigold,
                    )
                }
            }

            (state.connection as? Connection.Offline)?.let { offline ->
                OfflineBanner(
                    savedAt = offline.savedAt,
                    onRetry = onRetry,
                )
            }

            state.error?.let { message ->
                SectionCard(
                    section = TodaySection.Pension,
                    state = Loadable.Failed(message),
                    onRetry = onRetry,
                    empty = {},
                ) { /* unused */ }
            }

            // ------------------------------------------------------------------
            // 2. Check-in. Plan 6.2 card 2: hidden entirely if check-in is off.
            // ------------------------------------------------------------------
            if (checkIn.enabled && TodaySection.CheckIn !in state.hidden) {
                DoosriCheckInButton(
                    checkedAtLabel = checkIn.checkedAtLabel,
                    onClick = onCheckIn,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = checkIn.checkedAtLabel == null && !checkIn.sending,
                )
                // The design-system button has no "sending" state of its own, so
                // the fact that the tap is in flight is said here in words. A
                // button that silently stops responding is the single most common
                // way an app loses the trust of somebody who is not sure they
                // pressed it.
                when {
                    checkIn.sending -> StatusLine("Sending. Please wait a moment.", colors.inkSecondary)
                    checkIn.failed -> StatusLine(
                        "That did not send. It will go when you have a connection.",
                        colors.safety,
                    )
                    checkIn.paused -> StatusLine(
                        "Check-ins are paused. Nobody will be alerted while you are away.",
                        colors.inkSecondary,
                    )
                }
            }

            // ------------------------------------------------------------------
            // Cards 3–10, in the order plan 6.2's ranking decided on.
            // ------------------------------------------------------------------
            state.order
                .filterNot { it in state.hidden }
                .forEach { section ->
                    when (section) {
                        TodaySection.CheckIn -> Unit

                        TodaySection.Pension -> {
                            SectionCard(
                                section = section,
                                state = state.pension,
                                onRetry = onRetry,
                                empty = { PensionCardEmpty() },
                            ) { card ->
                                PensionCardBody(
                                    card = card,
                                    prompt = state.creditPrompt,
                                    onAskCredit = { showCreditSheet = true },
                                    onNavigate = onNavigate,
                                )
                            }
                        }

                        TodaySection.Reminders -> SectionCard(
                            section = section,
                            state = state.reminders,
                            onRetry = onRetry,
                            empty = {
                                DoosriEmptyState(
                                    message = "Nothing is due today. Life certificate and tax dates will appear here.",
                                )
                            },
                        ) { reminders ->
                            reminders.forEach { item ->
                                DoosriListRow(
                                    icon = reminderIcon(item.kind),
                                    title = item.title,
                                    value = item.whenLabel,
                                    onClick = { onNavigate(TodayDestination.Reminder(item.id)) },
                                )
                            }
                        }

                        TodaySection.Practice -> SectionCard(
                            section = section,
                            state = state.practice,
                            onRetry = onRetry,
                            empty = {
                                DoosriEmptyState(
                                    message = "No practice scheduled today. Ten minutes of chair yoga whenever you like.",
                                    actionLabel = "Open the Yoga Kendra",
                                    onAction = { onNavigate(TodayDestination.Practice("kendra")) },
                                )
                            },
                        ) { practice ->
                            if (practice != null) {
                                DoosriListRow(
                                    icon = Icons.Outlined.SelfImprovement,
                                    title = "${practice.title} · ${practice.minutes} min",
                                    value = "Start",
                                    onClick = { onNavigate(TodayDestination.Practice(practice.id)) },
                                )
                                Text(
                                    text = practice.subtitle,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = colors.inkSecondary,
                                )
                            }
                        }

                        TodaySection.Circles -> SectionCard(
                            section = section,
                            state = state.circles,
                            onRetry = onRetry,
                            empty = {
                                DoosriEmptyState(
                                    message = "Nothing new in your circles today.",
                                )
                            },
                        ) { highlights ->
                            highlights.forEach { h ->
                                DoosriListRow(
                                    icon = circleIcon(h.kind),
                                    title = h.line,
                                    value = h.circle,
                                    onClick = { onNavigate(TodayDestination.Circle(h.id)) },
                                )
                            }
                        }

                        TodaySection.LiveRoom -> SectionCard(
                            section = section,
                            state = state.liveRoom,
                            onRetry = onRetry,
                            empty = { /* no live room is not news; the card simply does not appear */ },
                        ) { room ->
                            if (room != null) {
                                DoosriListRow(
                                    icon = Icons.Outlined.RecordVoiceOver,
                                    title = room.title,
                                    value = "${room.listeners} listening",
                                    onClick = { onNavigate(TodayDestination.Room(room.id)) },
                                )
                                Text(
                                    text = "Hosted by ${room.host}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = colors.inkSecondary,
                                )
                            }
                        }

                        TodaySection.Seva -> SectionCard(
                            section = section,
                            state = state.seva,
                            onRetry = onRetry,
                            empty = { /* one match a day, and today there is none */ },
                        ) { match ->
                            if (match != null) {
                                DoosriListRow(
                                    icon = Icons.Rounded.VolunteerActivism,
                                    title = match.title,
                                    value = "See",
                                    onClick = { onNavigate(TodayDestination.Seva(match.id)) },
                                )
                                Text(
                                    text = match.why,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = colors.inkSecondary,
                                )
                            }
                        }

                        TodaySection.Scam -> SectionCard(
                            section = section,
                            state = state.scam,
                            onRetry = onRetry,
                            empty = { /* the scam alert only exists when one is active */ },
                        ) { alert ->
                            if (alert != null) {
                                DoosriListRow(
                                    icon = Icons.Outlined.Campaign,
                                    title = alert.title,
                                    value = "Read",
                                    onClick = { onNavigate(TodayDestination.ScamShield) },
                                )
                                Text(
                                    text = alert.body,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = colors.inkSecondary,
                                )
                            }
                        }

                        TodaySection.Weather -> SectionCard(
                            section = section,
                            state = state.weather,
                            onRetry = onRetry,
                            empty = { DoosriEmptyState(message = "Weather is not available right now.") },
                        ) { weather ->
                            if (weather != null) {
                                DoosriListRow(
                                    icon = Icons.Outlined.WbSunny,
                                    title = "${weather.city} · ${weather.summary}",
                                    value = "${weather.minC}–${weather.maxC}°",
                                    onClick = { onNavigate(TodayDestination.Weather) },
                                )
                                weather.airQuality?.let { aqi ->
                                    DoosriListRow(
                                        icon = Icons.Outlined.Air,
                                        title = "Air quality",
                                        value = aqi,
                                        onClick = { onNavigate(TodayDestination.Weather) },
                                    )
                                }
                            }
                        }

                        TodaySection.News -> SectionCard(
                            section = section,
                            state = state.news,
                            onRetry = onRetry,
                            empty = { DoosriEmptyState(message = "No pension news for today.") },
                        ) { items ->
                            items.forEach { item ->
                                DoosriListRow(
                                    icon = Icons.Outlined.Newspaper,
                                    title = item.headline,
                                    value = item.source,
                                    onClick = { onNavigate(TodayDestination.News(item.id)) },
                                )
                            }
                        }
                    }
                }

            Text(
                text = "Doosri Pari is an independent app, not a government department.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.inkSecondary,
                modifier = Modifier.padding(top = DoosriDimens.md),
            )
        }
    }

    if (showCreditSheet) {
        DoosriSheet(
            title = "Did your pension credit?",
            onDismiss = { showCreditSheet = false },
        ) {
            Text(
                text = "We ask only on your credit day. If it has not arrived five times running, we will help you write a grievance.",
                style = MaterialTheme.typography.bodyLarge,
                color = colors.ink,
            )
            DoosriSecondaryButton(
                text = "Yes, the money came",
                onClick = {
                    showCreditSheet = false
                    onCreditAnswer(true)
                },
                icon = Icons.Rounded.ArrowForward,
            )
            DoosriSecondaryButton(
                text = "Not yet",
                onClick = {
                    showCreditSheet = false
                    onCreditAnswer(false)
                },
                icon = Icons.Outlined.Schedule,
            )
            DoosriSecondaryButton(
                text = "Ask me another day",
                onClick = { showCreditSheet = false },
            )
        }
    }

    if (showEditSheet) {
        DoosriSheet(title = "Edit today", onDismiss = { showEditSheet = false }) {
            Text(
                text = "Hide a card and it stays hidden until you bring it back. Nothing is deleted.",
                style = MaterialTheme.typography.bodyLarge,
                color = colors.inkSecondary,
            )
            TodaySection.entries
                .filterNot { it == TodaySection.CheckIn }
                .forEach { section ->
                    val hidden = section in state.hidden
                    DoosriListRow(
                        icon = Icons.Outlined.Tune,
                        title = section.label,
                        value = if (hidden) "Hidden" else "Shown",
                        onClick = { onHideSection(section, !hidden) },
                    )
                }
        }
    }
}

// ---------------------------------------------------------------- the cards --

/**
 * One card, in one of four states.
 *
 * `Ready` with a null or empty value renders [empty] rather than an empty card,
 * because a card with a header and nothing under it reads as a fault. The
 * `Cache` line is not optional: plan 13 says Today works offline, and a pensioner
 * looking at a DR rate has a right to know whether it is today's.
 */
@Composable
private fun <T> SectionCard(
    section: TodaySection,
    state: Loadable<T>,
    onRetry: () -> Unit,
    empty: @Composable () -> Unit,
    content: @Composable (T) -> Unit,
) {
    val colors = DoosriTheme.colors

    when (state) {
        Loadable.Loading -> {
            DoosriSectionHeader(section.label)
            DoosriCard(modifier = Modifier.fillMaxWidth(), onClick = null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(DoosriDimens.md),
                ) {
                    CircularProgressIndicator(
                        color = colors.accent,
                        modifier = Modifier.size(28.dp),
                    )
                    Text(
                        text = "Loading ${section.label.lowercase()}…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.inkSecondary,
                    )
                }
            }
        }

        is Loadable.Failed -> {
            DoosriSectionHeader(section.label)
            DoosriCard(modifier = Modifier.fillMaxWidth(), onClick = null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(DoosriDimens.md),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ErrorOutline,
                        contentDescription = null,
                        tint = colors.safety,
                        modifier = Modifier.size(32.dp),
                    )
                    Text(
                        text = state.message,
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.ink,
                    )
                }
                DoosriSecondaryButton(text = "Try again", onClick = onRetry)
            }
        }

        is Loadable.Ready -> {
            val value = state.value
            val isEmpty = value == null || (value is Collection<*> && value.isEmpty())
            if (isEmpty) {
                empty()
            } else {
                DoosriSectionHeader(section.label)
                if (state.source == FeedSource.Cache) {
                    CachedLine(state.cachedAt)
                }
                DoosriCard(modifier = Modifier.fillMaxWidth(), onClick = null) {
                    content(value)
                }
            }
        }
    }
}

@Composable
private fun CachedLine(cachedAt: Instant?) {
    val colors = DoosriTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DoosriDimens.sm),
    ) {
        Icon(
            imageVector = Icons.Rounded.CloudOff,
            contentDescription = null,
            tint = colors.inkSecondary,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = cachedAt?.let { "Saved on this phone at ${it.toLocalDateTime(TimeZone.currentSystemDefault()).date}" }
                ?: "Saved on this phone",
            style = MaterialTheme.typography.bodySmall,
            color = colors.inkSecondary,
        )
    }
}

@Composable
private fun OfflineBanner(savedAt: Instant?, onRetry: () -> Unit) {
    val colors = DoosriTheme.colors
    DoosriCard(modifier = Modifier.fillMaxWidth(), onClick = null) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DoosriDimens.md),
        ) {
            Icon(
                imageVector = Icons.Rounded.CloudOff,
                contentDescription = null,
                tint = colors.inkSecondary,
                modifier = Modifier.size(32.dp),
            )
            Text(
                // Plain words, not a spinner. Plan 13 puts Today in the offline
                // column precisely so a pensioner on a train is not looking at a
                // loading ring for a DR rate that has not changed in six months.
                text = if (savedAt != null) {
                    "No connection. Showing what this phone saved on ${savedAt.toLocalDateTime(TimeZone.currentSystemDefault()).date}."
                } else {
                    "No connection, and nothing saved on this phone yet."
                },
                style = MaterialTheme.typography.bodyLarge,
                color = colors.ink,
            )
        }
        DoosriSecondaryButton(text = "Try again", onClick = onRetry)
    }
}

@Composable
private fun StatusLine(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(text = text, style = MaterialTheme.typography.bodyMedium, color = color)
}

/**
 * The pension card.
 *
 * ## The boundary, in the place it will be broken
 *
 * The rate, the credit day and the headline are server data and are ordinary
 * list rows. The rupee figures at the bottom are **this phone's**, read from the
 * on-device document safe, and they are separated here by a divider, a lock icon
 * and a sentence that says so — not by a comment alone.
 *
 * That is deliberate. A later edit that turns `local` into a field of the feed
 * DTO, or that drops the `local` branch because the figures "should be on the
 * server for backup", compiles cleanly, passes every test in the repository, and
 * puts a pensioner's net pension into a Postgres table that plan section 10 says
 * must never hold it. The comment above `PensionSnapshot` explains the rule; this
 * is where somebody will actually be when they decide to break it, so the rule is
 * said on the screen as well as in the type.
 */
@Composable
private fun PensionCardBody(
    card: PensionCard,
    prompt: CreditPrompt,
    onAskCredit: () -> Unit,
    onNavigate: (TodayDestination) -> Unit,
) {
    val colors = DoosriTheme.colors

    DoosriListRow(
        icon = Icons.Outlined.Savings,
        title = "DR ${rateLabel(card.dr.ratePercent)}% · in force from ${card.dr.effectiveFrom.toLocalDateTime(TimeZone.currentSystemDefault()).date}",
        value = card.dr.sourceNote,
        onClick = { onNavigate(TodayDestination.Sahayak) },
    )

    DoosriListRow(
        icon = Icons.Outlined.CreditCard,
        title = if (card.isCreditDayToday) "Pension due today" else "Next pension credit",
        value = card.nextCreditDate
            ?.toLocalDateTime(TimeZone.currentSystemDefault())
            ?.date
            ?.toString()
            ?: "You told us the ${card.creditDayOfMonth}th",
        onClick = { onNavigate(TodayDestination.Sahayak) },
    )

    card.headline?.let { headline ->
        DoosriListRow(
            icon = Icons.Outlined.Newspaper,
            title = headline.headline,
            value = headline.source,
            onClick = { onNavigate(TodayDestination.News(headline.id)) },
        )
    }

    HorizontalDivider(color = colors.divider)

    val local = card.local
    if (local != null && local.hasAnyFigure) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DoosriDimens.sm),
        ) {
            Icon(
                imageVector = Icons.Rounded.Lock,
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = "On this phone only",
                style = MaterialTheme.typography.titleSmall,
                color = colors.accent,
            )
        }
        local.netPensionRupees?.let { DoosriListRow(icon = Icons.Outlined.CreditCard, title = "Net pension", value = rupees(it)) }
        local.basicPensionRupees?.let { DoosriListRow(icon = Icons.Outlined.Savings, title = "Basic pension", value = rupees(it)) }
        Text(
            text = "These two figures are never sent anywhere. They were read from your pension slip on this phone, and they are not on our servers.",
            style = MaterialTheme.typography.bodySmall,
            color = colors.inkSecondary,
        )
    } else {
        Text(
            text = "Your own pension amount is not shown here. Read your slip once and it is saved on this phone, and nowhere else.",
            style = MaterialTheme.typography.bodyLarge,
            color = colors.inkSecondary,
        )
        DoosriSecondaryButton(
            text = "Read a pension slip",
            onClick = { onNavigate(TodayDestination.Toolkit) },
            icon = Icons.Outlined.Groups,
        )
    }

    when (prompt) {
        CreditPrompt.NotToday, is CreditPrompt.Answered -> Unit

        CreditPrompt.Due -> DoosriSecondaryButton(
            text = "Did your pension credit?",
            onClick = onAskCredit,
            icon = Icons.Outlined.Event,
        )

        is CreditPrompt.OfferGrievance -> {
            Text(
                text = "You have told us ${prompt.noStreak} times that the money has not come. Let us help you write a grievance.",
                style = MaterialTheme.typography.bodyLarge,
                color = colors.ink,
            )
            DoosriSecondaryButton(
                text = "Open the grievance helper",
                onClick = { onNavigate(TodayDestination.Grievance) },
                icon = Icons.Outlined.ChevronRight,
            )
        }
    }
}

@Composable
private fun PensionCardEmpty() {
    DoosriEmptyState(
        message = "Your pension details are not set up yet. Add your credit day to see the DR rate and the next credit date here.",
        actionLabel = "Set up pension details",
        onAction = { /* routed by the host through the toolkit */ },
    )
}

private fun reminderIcon(kind: ReminderKind): ImageVector = when (kind) {
    ReminderKind.LifeCertificate -> Icons.Outlined.Event
    ReminderKind.Tax -> Icons.Outlined.CreditCard
    ReminderKind.CghsRenewal -> Icons.Outlined.Notifications
    ReminderKind.Medical -> Icons.Outlined.Notifications
    ReminderKind.CommutationRestoration -> Icons.Outlined.Schedule
    ReminderKind.AdditionalPension -> Icons.Outlined.Savings
    ReminderKind.Other -> Icons.Outlined.Notifications
}

private fun circleIcon(kind: CircleHighlightKind): ImageVector = when (kind) {
    CircleHighlightKind.Post -> Icons.Outlined.Groups
    CircleHighlightKind.Birthday -> Icons.Outlined.Event
    CircleHighlightKind.Retirement -> Icons.Outlined.SelfImprovement
}

/** "58" not "58.0" — a DR rate is a whole-number fact to the person reading it. */
private fun rateLabel(percent: Double): String =
    if (percent % 1.0 == 0.0) percent.toLong().toString() else percent.toString()

/** Indian grouping, as in the plan's shared core. A pensioner reads lakhs, not 486000. */
private fun rupees(amount: Double): String {
    val digits = kotlin.math.abs(amount).toLong().toString()
    val grouped = if (digits.length <= 3) {
        digits
    } else {
        val head = digits.dropLast(3).reversed().chunked(2).joinToString(",").reversed()
        "$head,${digits.takeLast(3)}"
    }
    return "₹$grouped"
}

// ------------------------------------------------------------------- route --

/**
 * The stateful entry point, shaped like `CheckInRoute`.
 *
 * `modifier` is the first parameter because the navigation shell binds this
 * function by the name and first parameter `TodayRoute(modifier: Modifier =
 * Modifier)`; everything after it is an optional override so a test can pass a
 * fake for each dependency and the shell can pass none. The check-in is *not* a
 * Today dependency: it is a second ViewModel, and this route only narrows its
 * state. See [TodayCheckIn].
 *
 * With no overrides the route composes against [NotWired] ports rather than
 * throwing. Those ports do not invent data — they fail, so the screen shows its
 * honest "we could not reach the server" states with a Try again button, which
 * is the truth about a build whose `data/` layer is still being written. The
 * moment the host has a production repository it passes it here and the
 * fallbacks go unused.
 */
@Composable
fun TodayRoute(
    modifier: Modifier = Modifier,
    feedRepository: TodayFeedRepository? = null,
    onDevicePensionSource: OnDevicePensionSource? = null,
    checkInRepository: CheckInRepository? = null,
    todayViewModel: TodayViewModel? = null,
    checkInViewModel: CheckInViewModel? = null,
    onSos: () -> Unit = {},
    onNavigate: (TodayDestination) -> Unit = {},
    /** Plan 3.2 puts "read this screen aloud" on every screen. The TTS engine is not wired yet. */
    onReadAloud: () -> Unit = {},
) {
    val vm = todayViewModel ?: remember(feedRepository, onDevicePensionSource) {
        TodayViewModel(
            feedRepository ?: NotWired.Feed(),
            onDevicePensionSource ?: NotWired.Pension(),
        )
    }

    val state by vm.state.collectAsStateWithLifecycle()

    val cvm = checkInViewModel ?: remember(checkInRepository) {
        CheckInViewModel(
            checkInRepository ?: NotWired.CheckIn(),
            LocalDay.today(),
        )
    }
    val checkInState by cvm.state.collectAsStateWithLifecycle()

    val checkIn = remember(checkInState) {
        val snapshot = checkInState.today
        TodayCheckIn(
            checkedAtLabel = snapshot?.checkedAt?.let {
                "Checked in at ${it.toLocalDateTime(TimeZone.currentSystemDefault()).let { l -> "${l.hour12()}:${l.minute.toString().padStart(2, '0')}" }}"
            },
            sending = checkInState.sending,
            failed = checkInState.error != null,
            paused = checkInState.preferences?.isPaused == true,
        )
    }

    TodayScreen(
        state = state,
        checkIn = checkIn,
        onCheckIn = cvm::checkIn,
        onRetry = vm::retry,
        onHideSection = vm::setHidden,
        onCreditAnswer = vm::answerCredit,
        onNavigate = onNavigate,
        onSos = onSos,
        onReadAloud = onReadAloud,
        modifier = modifier,
    )
}

/**
 * Stand-in ports for a build whose `data/` layer is not written yet.
 *
 * Each one fails with an [java.io.IOException], which both ViewModels map to
 * their "could not reach" state — so a shell that calls `TodayRoute(modifier)`
 * before the production repositories exist gets the app's honest offline
 * screens with a Try again button, not a crash and not invented pension news.
 * That distinction matters here more than usual: the one thing this screen
 * must never do is show a pensioner a made-up DR rate.
 */
private object NotWired {

    // Nested names are deliberately short and distinct from the port names:
    // a nested `class TodayFeed` would shadow the `TodayFeed` data class in
    // its own override signature and fail to compile.

    class Feed : TodayFeedRepository {
        override suspend fun load(includeHidden: Set<TodaySection>): TodayFeed =
            throw java.io.IOException("Today feed is not connected yet")

        override suspend fun refresh(): TodayFeed =
            throw java.io.IOException("Today feed is not connected yet")

        override suspend fun setHidden(hidden: Boolean, section: TodaySection) = Unit

        override suspend fun recordCreditAnswer(credited: Boolean, on: String) = Unit
    }

    class Pension : OnDevicePensionSource {
        // The contract says "never throws": an empty snapshot is the honest
        // answer for a phone that has read no slip, and the pension card
        // already has copy for exactly that case.
        override suspend fun read(): PensionSnapshot = PensionSnapshot(
            basicPensionRupees = null,
            netPensionRupees = null,
            lastCreditedOn = null,
            readOn = null,
        )
    }

    class CheckIn : CheckInRepository {
        override suspend fun today(day: String): CheckInSnapshot =
            throw java.io.IOException("Check-in is not connected yet")

        override suspend fun checkIn(
            method: CheckInMethod,
            location: Pair<Double, Double>?,
        ): CheckInSnapshot = throw java.io.IOException("Check-in is not connected yet")

        override suspend fun preferences(): CheckInPreferences =
            throw java.io.IOException("Check-in is not connected yet")

        override suspend fun history(limit: Int): List<CheckInSnapshot> =
            throw java.io.IOException("Check-in is not connected yet")
    }
}

private fun kotlinx.datetime.LocalDateTime.hour12(): Int =
    if (hour == 0) 12 else if (hour > 12) hour - 12 else hour
