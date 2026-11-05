package `in`.doosripari.settings

import kotlinx.datetime.Instant

/**
 * Typed consent, one grant per capability. Plan section 14.
 *
 * ## Why this is not a `Set<Boolean>`
 *
 * The Digital Personal Data Protection Act 2023, which plan section 14 names,
 * asks for **separate consents** for location, for health data, and for
 * guardian sharing, and asks that they be withdrawable in Settings. One "Share
 * with family" switch cannot satisfy that: a single yes is a yes to a son-in-law
 * learning his mother-in-law's medication schedule, and there is no way to take
 * back that part without taking back the part somebody genuinely consented to.
 *
 * So each capability is its own [GuardianCapability] and its own
 * [ConsentGrant], and the UI is expected to render one switch per capability
 * with the wording that capability actually uses.
 *
 * ## Why a grant records a wording version
 *
 * This is the part that is easy to skip and is a legal requirement rather than
 * a nicety. A consent is an agreement to *specific text*. If the wording
 * changes — because a regulator required new disclosure, or because we realised
 * the old text did not say what we thought it said — then the old agreement no
 * longer covers the new text, and carrying it forward silently is consent the
 * user never gave.
 *
 * [ConsentGrant.wordingVersion] therefore pins what was agreed. When the app
 * ships wording version 2 for a capability, [ConsentRegistry.needsConsent]
 * returns true for anybody who agreed to version 1, and the app asks again.
 *
 * The alternative — storing a boolean and hoping the wording never changes —
 * is how apps end up with a consent record that no longer matches the thing it
 * was given for, which is precisely what a data-protection audit looks at.
 *
 * ## Withdrawal
 *
 * Plan section 14 requires withdrawal in Settings. [ConsentGrant] records a
 * withdrawal as its own timestamped state rather than deleting the row:
 * withdrawal history is itself part of the record, and a deleted row cannot
 * distinguish "never agreed" from "agreed and then took it back".
 */

/**
 * A capability that can be consented to, one at a time.
 *
 * The codes are the ones the server and the store share. They are `snake_case`
 * by convention rather than by a naming strategy — see `core-network`'s
 * `ApiClient.kt` for why this repository does not use one.
 */
enum class GuardianCapability(
    val code: String,
    /**
     * Whether a guardian cannot be told about a missed check-in at all without
     * this.
     *
     * Only [Alerts] is true, and that asymmetry is the point: every other
     * capability is an *addition* to what a guardian already knows, while
     * alerts are what the whole of plan section 6.3 is for. A user who
     * withdraws consent for location still has guardians who get the "all fine"
     * message. A user who withdraws consent for alerts has nobody, which the
     * app must then say plainly rather than pretend otherwise.
     */
    val isRequiredForEscalation: Boolean,
) {
    /** "Somebody will be told if you do not check in." Plan section 6.3. */
    Alerts("alerts", isRequiredForEscalation = true),

    /** "Somebody will be told where you are." Plan section 14. */
    Location("location", isRequiredForEscalation = false),

    /** "Somebody can see the emergency folder in your document safe." */
    DocumentSafe("document_safe", isRequiredForEscalation = false),

    /** "Somebody can see your medicine list." Plan section 14: health data. */
    Medicines("medicines", isRequiredForEscalation = false);

    /** What a user would see, in English, for a consent sheet. */
    val question: String
        get() = when (this) {
            Alerts -> "If you do not check in, should we tell your family?"
            Location -> "Should we share your location with your family?"
            DocumentSafe -> "Should your family be able to open your emergency documents?"
            Medicines -> "Should your family be able to see your medicines?"
        }

    companion object {
        val all: List<GuardianCapability> get() = entries

        fun fromCode(code: String?): GuardianCapability? =
            entries.firstOrNull { it.code == code }
    }
}

/**
 * One consent, and what it was a consent *to*.
 *
 * [wordingVersion] is the whole point; see the file KDoc. [grantedAt] and
 * [withdrawnAt] are both kept, so the record says when the agreement was made
 * and when it ended, and a live grant has [withdrawnAt] null.
 */
data class ConsentGrant(
    val capability: GuardianCapability,
    /** The version of the wording the user actually saw and agreed to. */
    val wordingVersion: Int,
    val grantedAt: Instant,
    val withdrawnAt: Instant? = null,
) {
    /** True when the agreement is currently in force. */
    val isActive: Boolean get() = withdrawnAt == null

    /**
     * Whether this grant still matches the wording the app now shows.
     *
     * False means the app must ask again rather than treat the old agreement
     * as covering the new text.
     */
    fun matches(wordingVersion: Int): Boolean = isActive && this.wordingVersion == wordingVersion
}

/** What the user has agreed to, per capability, with nothing implied. */
data class ConsentSet(val grants: Map<GuardianCapability, ConsentGrant> = emptyMap()) {

    /** The live grant for [capability], if it has one. */
    operator fun get(capability: GuardianCapability): ConsentGrant? =
        grants[capability]?.takeIf { it.isActive }

    /** Whether [capability] is currently agreed to, at the current wording. */
    fun isGranted(capability: GuardianCapability, wordingVersion: Int): Boolean =
        grants[capability]?.matches(wordingVersion) == true

    /**
     * Whether the app must ask again for [capability].
     *
     * True when there is no grant at all, and true when there is one but it was
     * given against older wording. The second case is the one a naive
     * implementation gets wrong, and it is the one that matters.
     */
    fun needsConsent(capability: GuardianCapability, wordingVersion: Int): Boolean =
        !isGranted(capability, wordingVersion)

    /**
     * Capabilities the app has to ask about right now, in enum order so the
     * sheet does not reshuffle between launches.
     */
    fun outstanding(wordingVersion: Int): List<GuardianCapability> =
        GuardianCapability.all.filter { needsConsent(it, wordingVersion) }

    /**
     * Whether anybody can be told if this user misses a check-in.
     *
     * Plan section 6.3's whole escalation chain depends on this being true, so
     * it is derived from the consent record rather than being a separate flag
     * that could drift away from it.
     */
    val canEscalate: Boolean
        get() = isGranted(
            capability = GuardianCapability.Alerts,
            wordingVersion = wordingVersionFor(GuardianCapability.Alerts),
        )

    /** Live grants only, for the guardian list and for a payload. */
    val activeGrants: List<ConsentGrant> get() = grants.values.filter { it.isActive }

    operator fun plus(grant: ConsentGrant): ConsentSet =
        copy(grants = grants + (grant.capability to grant))

    companion object {
        /**
         * The wording version this build shows, per capability.
         *
         * **Bump a number here when the text for that capability changes.** That
         * is the entire mechanism: the bump makes [ConsentSet.needsConsent]
         * true for everybody who agreed to the previous wording, and the app
         * asks them again. No migration, no flag day, no silent carry-over.
         *
         * Start at 1. There is no version 0, because "we never asked" and "we
         * asked version 0" are different states and only the first is true.
         */
        val WORDING_VERSIONS: Map<GuardianCapability, Int> = mapOf(
            GuardianCapability.Alerts to 1,
            GuardianCapability.Location to 1,
            GuardianCapability.DocumentSafe to 1,
            GuardianCapability.Medicines to 1,
        )

        /** The version this build shows for [capability]. */
        fun wordingVersionFor(capability: GuardianCapability): Int =
            WORDING_VERSIONS[capability] ?: 1

        val empty: ConsentSet = ConsentSet()
    }
}

/**
 * The consents a user must be asked for before the app does anything that
 * touches their data, and the question each one asks.
 *
 * Kept apart from [ConsentSet] on purpose. [ConsentSet] is data — what this
 * particular user agreed to. This is policy — what the current build asks
 * about. Keeping them apart means a wording change is one edit to a map, and
 * the user's history is untouched.
 */
object ConsentRegistry {

    /**
     * The order the consent sheet asks in.
     *
     * Alerts first, because everything else in plan section 6.3 hangs off it,
     * and because a user who declines it needs to be told immediately that
     * nobody will be told if they stop checking in — before they are asked about
     * anything else.
     */
    val order: List<GuardianCapability> = listOf(
        GuardianCapability.Alerts,
        GuardianCapability.Location,
        GuardianCapability.DocumentSafe,
        GuardianCapability.Medicines,
    )

    /**
     * Capabilities whose grant the app must have before it will send a
     * guardian anything at all.
     */
    fun escalationRequired(): List<GuardianCapability> =
        order.filter { it.isRequiredForEscalation }

    /** The question text for [capability], in the version this build ships. */
    fun question(capability: GuardianCapability): String = capability.question

    /**
     * What the app has to ask the user about right now.
     *
     * Empty means the app may proceed. Non-empty means the consent sheet must
     * come first — and if that is refused, [onRefusal] says what happens next,
     * because silently carrying on is the failure this type exists to stop.
     */
    fun outstandingFor(consents: ConsentSet): List<GuardianCapability> =
        order.filter { consents.needsConsent(it, ConsentSet.wordingVersionFor(it)) }

    /**
     * What withdrawing [capability] costs.
     *
     * Returning a consequence rather than a boolean is deliberate. A toggle
     * that turns off location and silently also stops the alerts is exactly the
     * bug plan section 14 is written to prevent, so the consequence is
     * something a screen can put in words.
     */
    fun onWithdrawal(capability: GuardianCapability): String = when (capability) {
        GuardianCapability.Alerts ->
            "If you turn this off, nobody will be told if you miss a check-in."
        GuardianCapability.Location ->
            "Your family will still be told if you miss a check-in, just not where you are."
        GuardianCapability.DocumentSafe ->
            "Your family will no longer be able to open your emergency documents."
        GuardianCapability.Medicines ->
            "Your family will no longer be able to see your medicines."
    }
}
