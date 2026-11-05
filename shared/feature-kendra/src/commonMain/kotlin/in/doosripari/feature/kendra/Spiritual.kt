package `in`.doosripari.feature.kendra

import `in`.doosripari.core.model.AppLanguage
import `in`.doosripari.core.model.Japa
import `in`.doosripari.core.model.Sankalp
import `in`.doosripari.core.model.SpiritualPath
import `in`.doosripari.core.model.SpiritualState
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.ceil
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

/**
 * Adhyatm Kendra, plan section 7.2 — and the whole file exists to hold the line
 * between recording somebody's practice and running it.
 *
 * ## Nothing in this module is spiritual content
 *
 * Not one mantra, not one prayer, not one verse, not one suggestion, not one
 * name. Every string a user sees here either comes off the server in
 * [ContentRef] or is a catalogue key in [Phrase]. That is not squeamishness, it
 * is the only arrangement that survives contact with ten languages and seven
 * traditions:
 *
 *  - **Plan section 7.2 requires openly licensed or public-domain material,
 *    each credited, otherwise a link out.** Content this module does not have is
 *    content it cannot have got wrong. [ContentRef] makes "no licence and no
 *    link" unrepresentable, so a translator or a content editor cannot produce
 *    a screen that quotes a copyrighted translation and credits nothing.
 *  - **The app does not instruct, correct or claim.** It does not tell somebody
 *    they must chant, or that a day missed matters, or that a deity is listening.
 *    Every function here reads what the user has chosen and what the server has
 *    supplied, and nothing more. [DailyStart] is the sharpest case: it is a
 *    *suggestion*, and [DailyStart.isDescribedAsTradition] exists because the
 *    plan says a suggestion must be "described as tradition, never as a
 *    promise" — a promise is a claim, and a claim is the thing this app must
 *    never make about anybody's beliefs.
 *  - **No faith is ranked above another.** [Adhyatm] has no ordering of paths
 *    and no default. [SpiritualPath.None] and a user with seven paths selected
 *    are both ordinary states, and neither produces different behaviour beyond
 *    which suggestions are offered.
 *
 * ## Why there is no safety gate on this half of the module
 *
 * There is no [SafetyGate] call, no [SafetyFlag], no doctor's note and no health
 * check anywhere in this file, and their absence is deliberate. Plan section 7.1
 * is exercise on a mat, where a fall is a real risk. Plan section 7.2 is
 * somebody sitting where they already sit and counting. Reusing the yoga
 * module's vocabulary here — "contraindicated", "modified for your knee",
 * "check with your doctor" — over a japa count would be a category error the
 * user would feel in their hands.
 */
object Adhyatm {

    /**
     * The two-minute practice the plan asks for on the Today card.
     *
     * Two minutes is the plan's figure and it is also the largest number this
     * card will ever show. A spiritual practice this app suggests is a
     * suggestion; a thirty-minute one is a commitment the app has no business
     * making on somebody's behalf before breakfast.
     */
    val TODAY_PRACTICE: Duration = 2.minutes

    /**
     * The paths a user may hold, in the order the plan lists them.
     *
     * ## A core-model gap, and a real one
     *
     * Plan section 7.2 says "Hindu, Muslim, Sikh, Christian, Buddhist, Jain,
     * Jewish, or several". core-model's `SpiritualPath` has `Shaiva`,
     * `Vaishnava` and `Shakta` — which together cover Hindu — plus `Sikh`,
     * `Jain`, `Buddhist` and a `General`, and **no entry at all for Muslim,
     * Christian or Jewish**.
     *
     * That is not a cosmetic omission. A user who selects "Muslim" and finds
     * that the option is not there is not given a worse experience, they are
     * told by the app that their religion was not among the ones it thought to
     * include. It is the single most consequential gap this module has found,
     * it cannot be fixed from here (core-model is out of scope for this task),
     * and it is reported rather than worked around with invented enum values.
     * [pathsSupportedByCoreModel] exists so a screen can detect the problem
     * instead of showing a truncated list as though it were the whole one.
     */
    val pathsSupportedByCoreModel: List<SpiritualPath> = SpiritualPath.entries

    /**
     * Whether [requested] can be stored at all.
     *
     * Always true today. It is here as the seam where the missing paths get
     * added, and it is a function rather than a set literal so that adding a
     * path to core-model does not also require editing the caller.
     */
    fun supports(path: SpiritualPath): Boolean = path in pathsSupportedByCoreModel

    /**
     * Today's spiritual start, plan section 7.2 item 2.
     *
     * **A suggestion, described as tradition.** All this does is narrow a
     * server-supplied list by weekday, by the paths the user has actually
     * chosen, and by language. It does not add, reorder by importance, invent,
     * or decide. If the server has nothing for this weekday, the answer is an
     * empty [DailyStart] and the card says so — the app does not fill the gap
     * from its own head, because anything it supplied from its own head would be
     * content it had not cleared and content it had not reviewed.
     */
    fun todayStart(
        supplied: List<DailyStartSuggestion>,
        state: SpiritualState,
        today: LocalDate,
        language: AppLanguage,
    ): DailyStart {
        val byDay = supplied.filter { it.isOfferedOn(today) }
        val forPath = byDay.filter { candidate -> candidate.paths.any { it in state.paths } }
        val inLanguage = forPath.filter { candidate -> candidate.languages.isEmpty() || language in candidate.languages }

        return DailyStart(
            date = today,
            suggestions = inLanguage,
            practice = DailyPractice(
                // Two minutes, from the plan, and only if there is something to
                // suggest. A "2-minute practice" line with nothing under it is
                // the emptiest possible card.
                length = if (inLanguage.isEmpty()) null else TODAY_PRACTICE,
                hasMantraOrPrayer = inLanguage.any { it.mantraOrPrayer != null },
            ),
            // Recorded, never claimed. The line the card shows, and it is a
            // description of a custom's origin.
            describedAsTradition = true,
        )
    }

    /**
     * The digital mala, plan section 7.2: 108, or 33 for tasbih.
     *
     * Counting is the whole feature and the only thing this returns. It does not
     * tell the user how many rounds to do, what happens when the mala is
     * finished, or that a day is missed.
     */
    fun japa(
        mantra: String,
        targetCount: Int,
        count: Int = 0,
        rounds: Int = Mala.DEFAULT_ROUNDS,
    ): Japa = Japa(
        mantra = mantra,
        targetCount = targetCount,
        count = count.coerceIn(0, maxOf(targetCount, 0)),
        rounds = rounds,
    )

    /**
     * A long sankalp — the plan's own example is 1,25,000 chants — and when it
     * would finish.
     *
     * [ratePerDay] is a rate the **user** has set, from a rate they have
     * observed, and there is no default. A device that assumed one would print
     * a date on somebody's screen that they did not choose, and a long practice
     * with somebody else's date on it is exactly the kind of thing that gets
     * abandoned in month two. Null when there is no rate, which is the honest
     * answer and not a failure.
     *
     * core-model's [SpiritualState] has nowhere to put a rate, which is a
     * core-model gap and is raised as a finding rather than worked around.
     */
    fun sankalpEstimate(
        sankalp: Sankalp,
        japa: Japa?,
        ratePerDay: Double?,
        now: Instant,
        timeZone: TimeZone,
    ): SankalpProgress? {
        if (!sankalp.isActiveAt(now)) return null
        if (japa == null || ratePerDay == null || ratePerDay <= 0.0) return null

        val remaining = (japa.targetCount - japa.count).coerceAtLeast(0)
        val daysLeft = ceil(remaining / ratePerDay).toLong()
        return SankalpProgress(
            sankalp = sankalp,
            remaining = remaining,
            estimatedDays = daysLeft,
            estimatedOn = now + daysLeft.days,
            timeZone = timeZone,
        )
    }

    /** Catalogued lengths of a mala, and the round each one completes. */
    object Mala {
        /** The plan's figure for a full mala. */
        const val DEFAULT_ROUNDS: Int = 108

        /** Tasbih. Plan section 7.2 names both. */
        const val TASBIH_ROUNDS: Int = 33

        fun roundsFor(practice: MalaPractice): Int = when (practice) {
            MalaPractice.Japa -> DEFAULT_ROUNDS
            MalaPractice.Tasbih -> TASBIH_ROUNDS
            MalaPractice.Single -> 1
        }
    }

    /** Which counting tradition a mala belongs to. A choice, not a recommendation. */
    enum class MalaPractice(val code: String) {
        Japa("japa"),
        Tasbih("tasbih"),
        Single("single");

        companion object {
            fun fromCode(code: String?): MalaPractice =
                entries.firstOrNull { it.code == code } ?: Japa
        }
    }
}

// ------------------------------------------------------------------ daily start --

/**
 * A suggestion for today, as the server supplied it.
 *
 * The type carries no text of its own. Every string is either a [ContentRef] to
 * something licensed and credited, or a [Phrase] key the content pipeline
 * published. That is what makes [Adhyatm.todayStart] a filter rather than an
 * author.
 *
 * [isDescribedAsTradition] is carried and checked rather than assumed, because
 * plan section 7.2's "described as tradition, never as a promise" is a
 * distinction that is very easy to lose in a copy edit and impossible to lose
 * once it is a constructor parameter.
 */
data class DailyStartSuggestion(
    val id: Int,
    val path: SpiritualPath,
    /** Which weekday this is offered on. Null means every day. */
    val weekday: Int? = null,
    val mantraOrPrayer: ContentRef?,
    val texts: List<ContentRef> = emptyList(),
    val languages: List<AppLanguage> = emptyList(),
    val relatedPaths: Set<SpiritualPath> = emptySet(),
    /**
     * The card must read as a description of a custom, not as a promise.
     * Enforced in [init], because a suggestion that has forgotten cannot be
     * corrected downstream by a screen that has no way to tell.
     */
    val isDescribedAsTradition: Boolean = true,
) {
    init {
        require(isDescribedAsTradition) {
            "plan 7.2: a suggestion is described as tradition, never as a promise"
        }
    }

    /**
     * Whether this is offered on [date]. A null [weekday] means every day, which
     * is the common case: most practices are not weekday-specific and pretending
     * otherwise would be a way of inventing structure.
     */
    fun isOfferedOn(date: LocalDate): Boolean = weekday == null || weekday == date.dayOfWeek.value
}

/** What the Today card shows, once the server's suggestions have been narrowed. */
data class DailyStart(
    val date: LocalDate,
    val suggestions: List<DailyStartSuggestion>,
    val practice: DailyPractice,
    /** Always true. See [DailyStartSuggestion.isDescribedAsTradition]. */
    val describedAsTradition: Boolean,
) {
    val isEmpty: Boolean get() = suggestions.isEmpty()

    /** The line above the list, as a key. No promise in it. */
    val header: Phrase get() = Phrase(KEYS.HEADER)

    companion object {
        /** What the card shows when the server has nothing for today. */
        fun empty(date: LocalDate): DailyStart = DailyStart(
            date = date,
            suggestions = emptyList(),
            practice = DailyPractice(length = null, hasMantraOrPrayer = false),
            describedAsTradition = true,
        )

        object KEYS {
            /** "Today, in the tradition of…" — a description, not an instruction. */
            const val HEADER: String = "kendra.spiritual.today_header"

            /** "Some traditions observe something today." */
            const val NOTHING_FOR_TODAY: String = "kendra.spiritual.nothing_for_today"

            /** "A two-minute practice, if you would like." An offer, never a nudge. */
            const val TWO_MINUTE_OFFER: String = "kendra.spiritual.two_minute_offer"
        }
    }
}

/**
 * The two-minute practice line.
 *
 * [length] is null when there is nothing to suggest, so a screen cannot draw a
 * "2 min" card with no content under it.
 */
data class DailyPractice(
    val length: Duration?,
    val hasMantraOrPrayer: Boolean,
) {
    val isAvailable: Boolean get() = length != null
}

// ---------------------------------------------------------------------- content --

/**
 * A pointer to content, never the content itself.
 *
 * ## The licence rule, made unrepresentable
 *
 * Plan section 7.2: Bhagavad Gita, Quran, Bible, Ramcharitmanas, Dhammapada,
 * Gurbani — "public-domain or openly licensed translations only", and Gurbani
 * specifically with "the licensing of each translation confirmed first".
 *
 * A screen that shows scripture needs three things: what it is, where it came
 * from, and whether it may be shown. So [ContentRef] requires a licence or an
 * external link, in [init], and a content editor who has not checked the
 * licensing of a Gurbani translation cannot get an object out of this class at
 * all. That is the whole point of putting the check in the constructor instead
 * of in a review checklist: a checklist is skipped under deadline, and a
 * constructor is not.
 */
data class ContentRef(
    val id: Int,
    /** The tradition's own name for it, as the catalogue records it. */
    val title: String,
    val path: SpiritualPath,
    /** The translation, which is the thing that carries the licence. */
    val translation: String? = null,
    val licence: ContentLicence,
    /** Who to credit. Required whenever the text may be shown. */
    val credit: String? = null,
    /**
     * Where to send somebody instead, when the text may not be shown here.
     *
     * Plan section 7.2's fallback: openly licensed and credited, **or a link
     * out**. A link is a better outcome than a blank screen and a better one
     * than a paragraph of somebody's copyrighted translation pasted into a
     * bundle.
     */
    val externalUrl: String? = null,
    val languages: List<AppLanguage> = emptyList(),
    /** Whether the text is in this app and can be read offline in large print. */
    val isReadableOffline: Boolean = false,
    val kind: ContentKind = ContentKind.Text,
) {
    init {
        require(licence.isClearToShow || externalUrl != null) {
            "plan 7.2: a text is shown only if it is public domain or openly " +
                "licensed and credited, otherwise it links out"
        }
        if (licence.isClearToShow) {
            require(!credit.isNullOrBlank()) {
                "plan 7.2: every text that is shown is credited"
            }
        }
    }

    /**
     * Whether a screen may render the words.
     *
     * False for [ContentLicence.Unverified] and for a reference that only has a
     * link. The screen shows the title, the credit where there is one, and the
     * button — which is a real screen and a real reading experience, not a
     * consolation prize.
     */
    val mayRenderText: Boolean get() = licence.isClearToShow && isReadableOffline

    /** Whether the screen's only affordance is a link out. */
    val isLinkOnly: Boolean get() = !mayRenderText

    companion object {
        /**
         * A text that is not cleared to be shown, and points somewhere that is.
         *
         * The normal case for a copyrighted translation, and the reason this
         * class can exist for every scripture the plan lists without the app
         * shipping a single word of any of them.
         */
        fun linkOut(
            id: Int,
            title: String,
            path: SpiritualPath,
            url: String,
            languages: List<AppLanguage> = emptyList(),
        ): ContentRef = ContentRef(
            id = id,
            title = title,
            path = path,
            licence = ContentLicence.LinkOutOnly,
            externalUrl = url,
            languages = languages,
            isReadableOffline = false,
        )
    }
}

/**
 * How cleared a text is to be shown.
 *
 * The important one is [Unverified], which exists so that "we have not checked"
 * is a representable state. Without it, an unchecked reference would have to be
 * filed under [OpenlyLicensed] — which is a lie — or under [Copyrighted], which
 * reads as a finding about somebody's work rather than as a gap in ours.
 */
enum class ContentLicence(val code: String) {
    /** No rights holder. Ramcharitmanas in the original Awadhi is the plan's example. */
    PublicDomain("public_domain"),

    /** Openly licensed, and the licence is named in the content row. */
    OpenlyLicensed("openly_licensed"),

    /** Permission obtained from the rights holder, recorded. */
    PermissionGranted("permission_granted"),

    /** Somebody has asserted the licence without evidence attached. */
    Asserted("asserted"),

    /** Not checked. The default, and the reason [ContentLicence] is an enum. */
    Unverified("unverified"),

    /** Rights reserved. Shown as a link out, never rendered. */
    Copyrighted("copyrighted"),

    /**
     * This app does not host it at all. The only state that is not a claim about
     * anybody's copyright, and the safest place for a content editor to leave a
     * reference while they find out.
     */
    LinkOutOnly("link_out_only");

    /**
     * Whether the words may be rendered inside the app.
     *
     * [Asserted] is deliberately false. An assertion with no licence text and no
     * permission record behind it is how a rights problem gets into a shipped
     * app, and plan section 7.2's condition is a licence, not somebody's
     * confidence.
     */
    val isClearToShow: Boolean
        get() = this == PublicDomain || this == OpenlyLicensed || this == PermissionGranted

    /** Whether this app should even offer to read it to the user. */
    val mayBeReadAloud: Boolean get() = isClearToShow

    companion object {
        /**
         * Unverified, not a guess.
         *
         * A missing or unrecognised licence string must not resolve to
         * [OpenlyLicensed] or to [PublicDomain], because both of those are
         * permissions and this is a lookup function.
         */
        fun fromCode(code: String?): ContentLicence =
            entries.firstOrNull { it.code == code } ?: Unverified
    }
}

/** What kind of thing a [ContentRef] points at. */
enum class ContentKind(val code: String) {
    Text("text"),
    Reader("reader"),
    Audio("audio"),
    /** A sung recording. Plan section 7.2: openly licensed, credited, never a robot voice. */
    SungDevotion("sung_devotion"),
    Prayer("prayer");

    /** Whether the words should be shown large, per plan section 7.2's "large print". */
    val prefersLargePrint: Boolean get() = this == Text || this == Reader

    companion object {
        fun fromCode(code: String?): ContentKind? = entries.firstOrNull { it.code == code }
    }
}

// ------------------------------------------------------------------ the counting --

/**
 * The count.
 *
 * A counter with a target and no commentary. It does not congratulate, it does
 * not say the mala is finished, it does not say anything about yesterday, and it
 * has no notion of a streak — [MalaView.remaining] is a number and the screen
 * puts a phrase key next to it.
 */
object MalaView {

    /**
     * Haptic strength for one bead.
     *
     * Plan section 7.2 asks for haptic taps. The strength is fixed rather than
     * configurable, because a setting screen for it is a setting screen nobody
     * with a hard time with their hands is going to find.
     */
    const val HAPTIC_STYLE: String = "rigid"

    /** How many beads before the round's end. */
    fun remaining(japa: Japa): Int = (japa.targetCount - japa.count).coerceAtLeast(0)

    /** Where through the target, 0..1. Zero rather than one for a target of zero. */
    fun progress(japa: Japa): Float = japa.progress.coerceIn(0f, 1f)

    /**
     * The next count.
     *
     * Clamped at the target, so a user who taps a hundred times past the end
     * does not end up with a count of 140 for a mala of 108 and a session log
     * that cannot be reconciled.
     */
    fun tap(japa: Japa, times: Int = 1): Japa = japa.copy(
        count = (japa.count + times).coerceIn(0, japa.targetCount),
    )

    /** The line the card shows. A key and a number, in that order. */
    fun countPhrase(japa: Japa): Phrase = Phrase(
        key = "kendra.spiritual.japa_count",
        args = mapOf("count" to japa.count.toString()),
    )
}

/**
 * A long sankalp and when it might finish.
 *
 * [estimatedOn] is only ever produced from a rate the user supplied, and
 * [isEstimated] is carried so a screen says "about" — see
 * [Adhyatm.sankalpEstimate], which returns null rather than inventing a rate.
 */
data class SankalpProgress(
    val sankalp: Sankalp,
    val remaining: Int,
    val estimatedDays: Long,
    val estimatedOn: Instant,
    /** The user's own timezone, so the printed date is the date they live in. */
    val timeZone: TimeZone,
) {
    val isEstimated: Boolean get() = true

    /** The estimate, as a phrase with the number. "About" is in the string. */
    val estimatePhrase: Phrase get() = Phrase(
        key = "kendra.spiritual.sankalp_estimate",
        args = mapOf(
            "days" to estimatedDays.toString(),
            "on" to estimatedOn.toLocalDateTime(timeZone).date.toString(),
        ),
    )

    /**
     * Whether the sankalp is still open. Not "on track" and not "behind" —
     * those are judgements about somebody's life, and this app has no basis for
     * making them.
     */
    val isStillOpen: Boolean get() = remaining > 0
}

/**
 * Where the "evening before" line for a practice lands, and the hour.
 *
 * Shared with [Festivals], which uses the same hour for the same reason: one
 * evening reminder, at a time when somebody is actually awake and at home, and
 * not a second notification at a different time that trains people to swipe
 * them away.
 */
object PracticeReminders {
    val EVENING_BEFORE: LocalTime = LocalTime(19, 0)

    /**
     * Whether an evening-before reminder is still wanted.
     *
     * Once it has been sent for this occurrence, never again for the same one —
     * a second notification about the same fast trains people to swipe
     * reminders away, and the fast does not care whether they were reminded.
     *
     * Quiet hours are [ReminderPolicy]'s business and not this function's: it
     * decides whether the reminder is wanted, `feature-health` decides when it
     * may be delivered, and having both would be two rules that could disagree.
     */
    fun isWanted(alreadySentForThisOccurrence: Boolean): Boolean = !alreadySentForThisOccurrence
}
