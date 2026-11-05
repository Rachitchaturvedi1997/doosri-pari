package `in`.doosripari.core.model

import kotlinx.datetime.Instant

/**
 * Who a person is, and what they are allowed to do. Plan sections 6.1 and 10.
 *
 * The important thing in this file is what is NOT here: no Aadhaar, no PPO
 * number, no bank account. Plan section 14 is explicit that the full numbers
 * never reach our servers — only a last-four and a verification tier, so that
 * "somebody who is verified" can be established without anybody's identity
 * document sitting in a database we would then have to defend.
 */

/** Which service a pensioner retired from. Filters every Sahayak answer. */
enum class ServiceType(val code: String, val label: String) {
    CentralCivil("central_civil", "Central government"),
    Defence("defence", "Defence services"),
    StateCivil("state_civil", "State government"),
    PublicSector("psu", "Public sector"),
    Bank("bank", "Banking"),
    Railway("railway", "Railways"),
    Police("police", "Police services"),
    Teaching("teaching", "Teaching"),
    Other("other", "Other");

    companion object {
        fun fromCode(code: String?): ServiceType =
            entries.firstOrNull { it.code == code } ?: Other
    }
}

/**
 * Verification tier. Plan section 6.1: what somebody may host, vouch, or be
 * trusted with. Ordered by [rank] so a check reads as a comparison rather than
 * as a set membership test.
 */
enum class Tier(val code: String, val rank: Int) {
    /** Signed up. Can check in and be escalated to. Can host nothing. */
    T0("T0", 0),

    /** Vouched for by an existing member, or a verified phone number. */
    T1("T1", 1),

    /** Document verified — last four of a PPO or service ID only. */
    T2("T2", 2),

    /** Admin verified. Hosts voice rooms, moderates circles. */
    T3("T3", 3);

    companion object {
        fun fromCode(code: String?): Tier = entries.firstOrNull { it.code == code } ?: T0
    }
}

/** How a person proved who they are. Plan section 6.1. */
enum class VerificationMethod(val code: String) {
    PhoneOtp("phone_otp"),
    Google("google"),
    Apple("apple"),
    Vouch("vouch"),
    Document("document"),
    Admin("admin"),
}

/** A government department, and the aliases people actually type. */
data class Department(
    val id: Int,
    val name: String,
    val parentId: Int? = null,
    val serviceType: ServiceType = ServiceType.Other,
    /**
     * "Railways" is typed as "rail", "Rly", "रेल" and "Railway Board". Without
     * these, a search for one's own department comes up empty, and the Sahayak
     * then answers the wrong service's rules.
     */
    val aliases: List<String> = emptyList(),
)

/**
 * A user's own record as this device knows it.
 *
 * [addressAs] is how the app should greet them — "Sharma ji", "Col. Kaur".
 * Plan section 3.5 asks for the respectful form rather than the first name,
 * because getting that wrong is the fastest way to lose a 65-year-old user on
 * their first screen.
 */
data class UserProfile(
    val id: Int,
    val phoneE164: String? = null,
    val name: String? = null,
    val addressAs: String? = null,
    val language: AppLanguage = AppLanguage.English,
    val textScale: TextScale = TextScale.System,
    val serviceType: ServiceType = ServiceType.Other,
    val stateCode: String? = null,
    val departmentId: Int? = null,
    val tier: Tier = Tier.T0,
    val cityId: Int? = null,
    val lat: Double? = null,
    val lng: Double? = null,
    val discoverable: Boolean = false,
    val timeZone: String = "UTC",
    val createdAt: Instant? = null,
) {
    /** What to put on the Today screen's greeting. Never a bare first name. */
    val greetingName: String
        get() = addressAs?.takeIf { it.isNotBlank() }
            ?: name?.substringBefore(' ')?.takeIf { it.isNotBlank() }
            ?: "friend"
}

/** A device that can receive a push. Plan section 10, `devices`. */
data class Device(
    val id: Int,
    val platform: Platform,
    val pushToken: String,
    val appVersion: String? = null,
    val lastSeenAt: Instant? = null,
)

enum class Platform(val code: String) {
    Android("android"),
    Ios("ios");

    companion object {
        fun fromCode(code: String?): Platform =
            entries.firstOrNull { it.code == code } ?: Android
    }
}

/**
 * A verification record.
 *
 * [last4] is four characters and that is the whole point. Plan section 14:
 * the full number never reaches our server, the document is deleted within
 * seven days, and what is kept is enough to answer "has this person been
 * verified" without enough to impersonate them.
 */
data class Verification(
    val id: Int,
    val tier: Tier,
    val method: VerificationMethod,
    /** Who did it — a vouching member, or an admin id. */
    val byUserId: Int? = null,
    val last4: String? = null,
    val verifiedAt: Instant? = null,
    val notes: String? = null,
) {
    /**
     * A verification with no [last4] and no [byUserId] is not evidence of
     * anything; it can only have come from a phone OTP.
     */
    val isEvidence: Boolean get() = last4 != null || byUserId != null
}

/**
 * A vouch: one member saying another is who they say they are. Plan section
 * 6.1. Vouching is what moves somebody from T0 to T1, and it is deliberately
 * weaker than a document check, so it is typed separately.
 */
data class Vouch(
    val id: Int,
    val voucherId: Int,
    val voucheeId: Int,
    val departmentId: Int? = null,
    val createdAt: Instant? = null,
)

/**
 * Somebody who gets told when a check-in is missed.
 *
 * Plan section 6.3 caps this at five and requires each to accept. [acceptedAt]
 * being null means the invite is outstanding and NO alert is sent to that
 * number — sending a panic message to somebody who never agreed to it is both
 * a privacy problem and the fastest way to get a phone number blocked.
 */
data class Guardian(
    val id: Int,
    val name: String,
    val phone: String,
    val relation: String? = null,
    /** 1-based; the order they are telephoned in. */
    val order: Int = 1,
    val permissions: GuardianPermissions = GuardianPermissions(),
    val acceptedAt: Instant? = null,
) {
    val isActive: Boolean get() = !acceptedAt.isNullOrBlank()

    /** True only when this guardian may be told where the user last was. */
    val getsLocation: Boolean get() = isActive && permissions.location
}

/**
 * Guardian permissions are three separate consents, not one. Plan section 14
 * asks for them separately, and a single "share with family" switch is how apps
 * end up telling a son-in-law his mother-in-law's medication schedule.
 */
data class GuardianPermissions(
    /** Told when a check-in is missed. Every active guardian has this. */
    val alerts: Boolean = true,
    /** Told where the user is, and only if the user also shares location. */
    val location: Boolean = false,
    /** Sees the emergency folder in the document safe. */
    val documentSafe: Boolean = false,
    /** Sees the medicine log. */
    val medicines: Boolean = false,
)

/** A guardian's view of somebody they look after. */
data class GuardianDashboard(
    val protectedUser: UserProfile,
    val lastCheckInAt: Instant? = null,
    /** Null when everything is fine; otherwise what stage it has reached. */
    val concernStage: String? = null,
    /** Shared with this guardian's own permission — never with a server default. */
    val lastKnownLocation: GeoPoint? = null,
    /** Open SOS events for this user. */
    val openSos: List<SosSummary> = emptyList(),
)

/** A live SOS, as a guardian sees it. Plan section 6.3. */
data class SosSummary(
    val id: Int,
    val startedAt: Instant,
    val location: GeoPoint? = null,
    /** Which channels actually got through. A failed SMS is shown as failed. */
    val delivered: List<DeliveryChannel> = emptyList(),
    val resolvedAt: Instant? = null,
)

/** The channels an alert can go out on. Plan section 21 risk 2: one can fail. */
enum class DeliveryChannel(val code: String) {
    Push("push"),
    Sms("sms"),
    Whatsapp("whatsapp"),
    IvrCall("ivr_call");

    companion object {
        fun fromCode(code: String): DeliveryChannel? =
            entries.firstOrNull { it.code == code }
    }
}
