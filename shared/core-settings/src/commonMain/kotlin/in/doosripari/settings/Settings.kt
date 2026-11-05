package `in`.doosripari.settings

import `in`.doosripari.core.model.AppLanguage
import `in`.doosripari.core.model.TextScale

/**
 * Everything a user has chosen, in one immutable value.
 *
 * ## Plain Kotlin, no serialization
 *
 * Nothing here is `@Serializable`, for the same reason nothing in `core-model`
 * is: the shape on disk is the store's business, not the domain's. The
 * DataStore-backed adapter in `androidMain` is where keys become strings; this
 * file is what the rest of the app sees.
 *
 * ## Text scale composes with the system, and that is the whole point
 *
 * Plan section 4.3 sets a 20 pt body before Dynamic Type and plan section 6.16
 * says "System settings respected: Dynamic Type and the Android font scale up to
 * the largest sizes". A 68-year-old may already have their phone at maximum,
 * because they have been enlarging it for a decade. If this app *replaced*
 * that with its own scale, the user who needed the biggest text would get the
 * smallest — the setting they went out of their way to set would be ignored.
 *
 * So [TextScale] is a multiplier *on top of* the system's, and
 * [composedTextScale] multiplies. [TextScale.System] means "leave the system
 * alone", not "use 1.0". See the function KDoc for the arithmetic.
 *
 * ## Local time, stored as minutes
 *
 * The check-in window is a LOCAL window (plan section 6.3: a 09:00 window three
 * hours long, in the user's own time), and a window has no meaning without a
 * timezone. Storing "09:00" rather than an instant means moving city does not
 * silently move the window; the timezone is applied at use, by
 * `feature-checkin`, which already owns that arithmetic. Minutes past local
 * midnight rather than `LocalTime` because this module has no date dependency
 * and a pair of ints survives a timezone database update better than a string
 * ever will.
 */

/** Plan section 4.3's check-in window, as the user sets it. */
data class CheckInWindow(
    /** Local minutes past midnight. Default 09:00, per plan section 6.3. */
    val startMinuteOfDay: Int = DEFAULT_START_MINUTE_OF_DAY,
    /** How long it stays open. Default 180 minutes. */
    val minutes: Int = DEFAULT_MINUTES,
    /** Travel or hospital; no reminders, no calls, no alerts. */
    val pausedUntilEpochSeconds: Long? = null,
) {
    init {
        require(startMinuteOfDay in 0..MINUTES_IN_DAY) {
            "startMinuteOfDay must be 0..$MINUTES_IN_DAY, was $startMinuteOfDay"
        }
        require(minutes in 1..MAX_MINUTES) {
            "minutes must be 1..$MAX_MINUTES, was $minutes"
        }
    }

    /** The window as "09:00", for a screen to show. */
    fun startAsClockText(): String {
        val h = startMinuteOfDay / 60
        val m = startMinuteOfDay % 60
        return "${h.toString().padStart(2, '0')}:${m.toString().padStart(2, '0')}"
    }

    /** When the window closes, in minutes past local midnight. May exceed a day. */
    val endMinuteOfDay: Int get() = (startMinuteOfDay + minutes) % MINUTES_IN_DAY

    /** True when the window runs past midnight, so the screen must say so. */
    val wrapsMidnight: Boolean get() = startMinuteOfDay + minutes >= MINUTES_IN_DAY

    /** Whether a pause is in force at [nowEpochSeconds]. */
    fun isPausedAt(nowEpochSeconds: Long): Boolean =
        pausedUntilEpochSeconds?.let { nowEpochSeconds < it } ?: false

    companion object {
        /** 09:00, plan section 6.3's default. */
        const val DEFAULT_START_MINUTE_OF_DAY: Int = 9 * 60
        const val DEFAULT_MINUTES: Int = 180
        const val MAX_MINUTES: Int = 24 * 60
        const val MINUTES_IN_DAY: Int = 24 * 60

        val default: CheckInWindow = CheckInWindow()
    }
}

/**
 * Passive signals: app use, unlocks and steps counting as a check-in.
 *
 * Plan section 6.3 has these off by default, and the reason is worth keeping in
 * the type. A passive "check-in" is a claim that somebody is fine, made by a
 * phone rather than by a person. If the phone decides they are fine because it
 * saw two screen unlocks at a bus stop, nobody is told when they actually stop
 * answering — and that is the failure plan section 21 calls out as the top
 * risk. So every source is separately named and separately off.
 */
data class PassiveSignals(
    val enabled: Boolean = false,
    /** Any use of the app at all. */
    val appUse: Boolean = false,
    /** Two or more screen unlocks in the window. */
    val unlocks: Boolean = false,
    val steps: Boolean = false,
    /** Plan section 6.3: 200 steps. */
    val stepThreshold: Int = DEFAULT_STEP_THRESHOLD,
) {
    init {
        require(stepThreshold > 0) { "stepThreshold must be positive" }
    }

    /** True when nothing is actually being collected even if enabled. */
    val isEmpty: Boolean get() = enabled && !(appUse || unlocks || steps)

    /**
     * Whether a passive signal is plausible to record as a check-in.
     *
     * Requires [enabled] *and* at least one source, so a half-finished
     * settings screen cannot turn into a phone that checks the user in by
     * accident.
     */
    fun canRecordCheckIn(): Boolean = enabled && (appUse || unlocks || steps)

    companion object {
        const val DEFAULT_STEP_THRESHOLD: Int = 200
    }
}

/**
 * Location sharing, in three separate switches.
 *
 * Plan section 14 requires a separate consent for location. Keeping it separate
 * from [ConsentSet] would be duplication, so these are the *settings* and
 * [ConsentSet] is the *agreement*; [in doosripari.settings.ConsentRegistry] is
 * what ties them together. Both have to agree before anything is sent.
 */
data class LocationSharing(
    /** Store the last known position on the device. Default off. */
    val rememberLastKnown: Boolean = false,
    /** Send it to a guardian when a check-in is missed. Needs location consent. */
    val shareWithGuardiansOnMiss: Boolean = false,
    /** Let other members of a circle see an approximate area. Default off. */
    val shareWithCircle: Boolean = false,
) {
    /**
     * Whether a guardian may be sent this user's position.
     *
     * Both halves required, on purpose. A user can have withdrawn the consent
     * while leaving the setting on, and a screen that reads only the setting
     * would keep telling a son-in-law where his mother-in-law is.
     */
    fun mayShareWithGuardian(consents: ConsentSet): Boolean =
        shareWithGuardiansOnMiss &&
            consents.isGranted(
                capability = GuardianCapability.Location,
                wordingVersion = ConsentSet.wordingVersionFor(GuardianCapability.Location),
            )

    companion object {
        /** Nothing is shared unless the user turns something on. */
        val off: LocationSharing = LocationSharing()
    }
}

/**
 * Notification permission, as the platform actually reports it.
 *
 * [PermanentlyDenied] is separate from [Denied] because the recovery differs and
 * so does the message: a denied permission can be asked for again, a
 * permanently denied one cannot be changed without sending the user to system
 * settings. Telling somebody to "allow notifications" when the platform will
 * not ask again is a small betrayal.
 */
enum class PermissionState(val code: String) {
    /** Never asked. */
    NotAsked("not_asked"),

    /** The user said yes. */
    Granted("granted"),

    /** The user said no, and will be asked again. */
    Denied("denied"),

    /** The user said no, and the platform will not ask again. */
    PermanentlyDenied("permanently_denied");

    val isGranted: Boolean get() = this == Granted

    companion object {
        fun fromCode(code: String?): PermissionState =
            entries.firstOrNull { it.code == code } ?: NotAsked
    }
}

/**
 * What the app is allowed to interrupt for. Plan section 6.15.
 *
 * Plan section 6.15 is unusually specific — the app may interrupt for a
 * medicine, a check-in and a life certificate, and for nothing else. So this is
 * a set of explicit booleans rather than one "notifications on" flag: a circle
 * post must never be able to ring this phone, and a single flag would let it.
 */
data class NotificationPermissions(
    val push: PermissionState = PermissionState.NotAsked,
    /**
     * Whether a missed check-in may use a full-screen, time-sensitive alert.
     * Off by default: a full-screen alert that is not a missed check-in is the
     * fastest way to get notifications switched off, and then nothing gets
     * through.
     */
    val timeSensitiveAlerts: Boolean = false,
    /** Medicines. Plan section 6.15 says these may interrupt. */
    val medicines: Boolean = true,
    /** The daily check-in. Plan section 6.15 says these may interrupt. */
    val checkIn: Boolean = true,
    /** The life-certificate deadline. Once a year, and missing it costs a pension. */
    val lifeCertificate: Boolean = true,
    /** Appointments. */
    val appointments: Boolean = true,
    /** Circle activity. Batched into the daily digest — plan section 6.6. */
    val circleDigest: Boolean = true,
    /** Yoga sessions and everything else quiet. */
    val quiet: Boolean = true,
) {
    /**
     * Whether anything may ring the phone at all.
     *
     * Push permission is necessary and not sufficient: a granted permission with
     * every category off is a phone this app is not allowed to interrupt, and
     * saying "notifications are on" then would be a lie the user finds out at
     * the worst moment.
     */
    val mayInterrupt: Boolean
        get() = push.isGranted &&
            (medicines || checkIn || lifeCertificate || appointments || timeSensitiveAlerts)

    /** Categories that go into the quiet digest rather than interrupting. */
    val quietCategories: List<String> get() = buildList {
        if (circleDigest) add("circle_digest")
        if (quiet) add("quiet")
    }
}

/**
 * Everything, together.
 *
 * Immutable and `val`-only, like every other domain type in this repository. A
 * settings screen that mutates one field of a shared object in place is how two
 * screens end up disagreeing about what the user chose.
 */
data class AppSettings(
    val language: AppLanguage = AppLanguage.default,
    val textScale: TextScale = TextScale.default,
    val checkInWindow: CheckInWindow = CheckInWindow.default,
    val passive: PassiveSignals = PassiveSignals(),
    val location: LocationSharing = LocationSharing.off,
    val notifications: NotificationPermissions = NotificationPermissions(),
    val consents: ConsentSet = ConsentSet.empty,
) {
    /**
     * The text scale actually applied to type: this setting multiplied by
     * whatever the operating system says.
     *
     * [systemTextScale] is a parameter rather than a field because it is not
     * this app's to persist — it is the OS's, it can change while the app is
     * backgrounded, and storing a copy of it would guarantee the two drift.
     * Reading it on every composition is cheap; caching it is how an app ends up
     * ignoring a font-size change the user just made in Settings.
     */
    fun composedTextScale(systemTextScale: Float): Float =
        textScale.composeWith(systemTextScale)

    /** The body size plan section 4.3 asks for, before either scale applies. */
    fun bodySizePt(systemTextScale: Float): Float = BASE_BODY_PT * composedTextScale(systemTextScale)

    /** Whether the consent sheet has to be shown before anything else. */
    val hasOutstandingConsent: Boolean get() = ConsentRegistry.outstandingFor(consents).isNotEmpty()

    companion object {
        /**
         * Plan section 4.3: body text is 20 points before Dynamic Type. Not 14,
         * not 16 — the research in section 4.1 is about an audience that reads
         * a printed pension slip, not a phone.
         */
        const val BASE_BODY_PT: Float = 20f
    }
}

/**
 * The scale to actually render text at.
 *
 * **Multiplies, never replaces.** See the [AppSettings] KDoc: a user who has
 * already set their phone to the maximum font size must not have that decision
 * overridden by this app.
 *
 * The ceiling exists because multiplication is unbounded in principle: a phone
 * at 3.0 (which Android does offer, via accessibility font size) times
 * [TextScale.Largest] at 1.3 is 3.9×, which does not fit a card, and a layout
 * that overflows is a layout that hides the button somebody needed. Clamped at
 * 2.5, which is comfortably readable and still fits plan section 4.3's
 * card-and-sheet geometry.
 *
 * [TextScale.System] is the identity here — it means "I have no opinion", and
 * the system value passes straight through, whatever it is.
 */
const val MAX_COMPOSED_TEXT_SCALE: Float = 2.5f

/** The scale [this] setting produces on top of a system scale of [systemScale]. */
fun TextScale.composeWith(systemScale: Float): Float {
    val safe = if (systemScale.isFinite() && systemScale > 0f) systemScale else 1f
    return (safe * multiplier).coerceIn(MIN_TEXT_SCALE, MAX_COMPOSED_TEXT_SCALE)
}

/**
 * Android hands back 0, or a negative, on some OEM builds where the font scale
 * setting has been overridden. Flooring at 0.5 rather than at 1.0 lets a user
 * who has deliberately set their phone *smaller* keep that choice.
 */
private const val MIN_TEXT_SCALE: Float = 0.5f

