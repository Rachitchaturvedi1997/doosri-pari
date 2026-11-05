package `in`.doosripari.feature.circles

import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Saathi — circles, feed, comments, events and Chai pe Charcha. Plan sections
 * 6.6 and 6.7, written under the discipline of section 3 and section 15.
 *
 * ## Why these types live here rather than in `shared/core-model`
 *
 * Two reasons, and the second one is a to-do rather than a decision:
 *
 *  1. `android/app/build.gradle.kts` currently depends only on
 *     `:shared:feature-checkin`, which itself does not re-export
 *     `:shared:core-model`. Nothing in `in.doosripari.core.model` is on the
 *     app module's compile classpath, so importing it would not compile until
 *     somebody edits the build file — and this task may not edit existing
 *     files. The app-layer types are therefore declared here, shaped to what a
 *     screen needs rather than to what the database stores.
 *  2. **The names are deliberately different from the shared modules' names.**
 *     `shared/feature-circles` is also `in.doosripari.feature.circles`, and it
 *     already declares `Composer`, `Feed`, `RoomState`, `RoomRole`,
 *     `RoomSeat`, `RoomBlock`, `RecordingGate` and others. When the app module
 *     finally does depend on `:shared:feature-circles` and `:shared:core-model`,
 *     this package should be able to *use* those types rather than collide
 *     with them — a duplicate FQN across two modules on one classpath is a
 *     compile error, and a silent one at that. Hence `SaathiCircle` and not
 *     `Circle`, `RoomSeatView` and not `RoomSeat`, `ReportCause` and not
 *     `ReportReason`.
 *
 * The mapping when the dependency lands is mechanical: these types become the
 * network DTO, and the screens keep talking to their ViewModel.
 */

// ------------------------------------------------------------------ circles --

/** Plan section 6.6's circle types. */
enum class CircleType(val code: String, val label: String) {
    /** "Railways · retired 2019–21". Posting needs T1. */
    Batch("batch", "Batch"),
    Department("department", "Department"),
    City("city", "City"),
    /** A registered pensioners' association. Office-bearers approve members. */
    Association("association", "Association"),
    Interest("interest", "Interest"),
    /** An old office team. Invite link only. */
    Private("private", "Private"),
}

/**
 * How somebody gets in.
 *
 * The rule is carried on the type rather than checked at runtime so that a
 * screen can say *why* a join button is unavailable, which plan section 3's
 * "nothing hidden" turns into an obligation rather than a nicety.
 */
enum class JoinRule(val code: String, val label: String) {
    Open("open", "Open to everyone"),
    Vouched("vouched", "Two members vouch for you"),
    AssociationApproval("association_approval", "Office-bearers approve new members"),
    SameDepartment("same_department", "Same department only"),
    InviteOnly("invite_only", "Invite link only");

    val needsApproval: Boolean
        get() = this == AssociationApproval || this == InviteOnly
}

/**
 * A circle as the Saathi tab shows it: a name, how many people are in it, and
 * whether this user is already in it.
 *
 * No `departmentId`, no `cityId`, no `createdBy` — the row does not need them,
 * and every field on a screen is a field the server has to be able to justify
 * sending.
 */
data class SaathiCircle(
    val id: Int,
    val type: CircleType,
    val name: String,
    val memberCount: Int,
    val joinRule: JoinRule,
    val isJoined: Boolean = false,
    /** Requested, waiting for an office-bearer. */
    val isPending: Boolean = false,
    /** One line about the circle, written by whoever started it. */
    val about: String? = null,
) {
    val membershipLabel: String
        get() = when {
            isJoined -> "You are a member"
            isPending -> "Waiting for approval"
            else -> joinRule.label
        }
}

/**
 * ## Why every suggestion must carry a reason
 *
 * The plan's audience is retired government employees. These people are
 * *identifiable by composition* in a way almost no other online population is:
 * a department plus a cadre plus two posting years names a few hundred people
 * in the whole country, and a circle that materialises without an explanation
 * is indistinguishable from somebody who has worked out who the user is.
 *
 * That is a moderation problem before it is a design problem. Moderation works
 * by asking a user to judge content — "is this circle legitimate?", "is this
 * post a lure?" — and a user who cannot answer *why they are seeing this*
 * cannot answer that question either. An unexplainable recommendation
 * therefore trains the audience to click through things they cannot check,
 * which is precisely the habit that makes a pensioner a good target for the
 * scams section 1 describes.
 *
 * So the shape of the type is the policy: [SuggestionReason] has no null case
 * that silently means "the engine liked this". A suggestion arrives with either
 * a reason a user can check against their own life, or with
 * [SuggestionReason.NoReason], which the screen puts in its own clearly-labelled
 * group with a non-default join button. Nothing is shown unexplained and
 * quietly made joinable in one tap.
 */
sealed interface SuggestionReason {

    /** "You retired from Indian Railways in 2019." */
    data class SameBatch(val departmentName: String, val yearBand: String) : SuggestionReason

    /** "You listed Indian Railways as your department." */
    data class SameDepartment(val departmentName: String) : SuggestionReason

    /** "You said Lucknow is your home city." */
    data class SameCity(val cityName: String) : SuggestionReason

    /** "You chose Gardening as an interest." */
    data class ChosenInterest(val interest: String) : SuggestionReason

    /** "Sharma ji, who you have talked to, is a member." */
    data class KnownMember(val memberName: String, val howKnown: String) : SuggestionReason

    /** "There is a pensioners' association near you that has not posted yet." */
    data class NearYou(val cityName: String) : SuggestionReason

    /**
     * The engine could not produce a reason the user could check.
     *
     * Kept as a first-class value rather than being folded into one of the
     * others or dropped. Dropping it would hide a ranking bug from the people
     * best placed to notice it; dressing it up as one of the others would be a
     * lie the user later discovers they had been told. Both are worse than
     * saying "we are not sure".
     */
    data object NoReason : SuggestionReason
}

/** One card on the suggested-circles screen. */
data class CircleSuggestion(
    val circle: SaathiCircle,
    val reason: SuggestionReason,
    /** Members this user already knows who are inside. Shown as reassurance. */
    val knownMembers: List<String> = emptyList(),
)

/**
 * The sentence printed under a suggestion.
 *
 * Returns the *long* form, which names the fact the user entered themselves
 * ("You said Lucknow is your home city") rather than the model's internal
 * name ("same_city"). A recommendation explained in the vocabulary of the
 * feature that produced it is not explained at all.
 */
fun SuggestionReason.whyYouSeeThis(): String = when (this) {
    is SuggestionReason.SameBatch ->
        "You retired from $departmentName in $yearBand."
    is SuggestionReason.SameDepartment ->
        "You gave $departmentName as your department."
    is SuggestionReason.SameCity ->
        "You said $cityName is your home city."
    is SuggestionReason.ChosenInterest ->
        "You chose $interest in your interests."
    is SuggestionReason.KnownMember ->
        "$memberName, $howKnown, is a member."
    is SuggestionReason.NearYou ->
        "There is a pensioners' association in $cityName."
    SuggestionReason.NoReason ->
        "We are not sure why this is here. It is new, and nobody you know is in it yet."
}

// --------------------------------------------------------------------- feed --

enum class PostKind(val code: String, val label: String) {
    Text("text", "Message"),
    Photo("photo", "Photos"),
    Voice("voice", "Voice note"),
    Poll("poll", "Poll"),
    /** Plan section 6.6's "Old photo", which asks for a year and a place. */
    OldPhoto("old_photo", "Old photo"),
    Question("question", "Question"),
}

/**
 * One post in a feed.
 *
 * ## What a screen may do with [text]
 *
 * [text] is user-generated prose written by a stranger and read by a pensioner
 * who has just learned that most calls are frauds. It is the most dangerous
 * field in this app, and the handling rules are therefore properties of the
 * *type* wherever that is possible:
 *
 *  - [heldForReview] is not the same as [hidden]. A post held under section 15
 *    (a phone number or a payment request from a T0 member) is still shown to
 *    the reporter and to moderators, and to everybody else it renders as a
 *    notice rather than as silence — a missing post is indistinguishable from a
 *    deleted one, and this audience already distrusts silent deletion.
 *  - [linkHostnames] is pre-extracted rather than left for the UI to discover.
 *    A screen that linkifies at render time will eventually linkify something;
 *    this way the set of domains is known, reviewable, and can be checked
 *    against the blocklist in section 15 *before* anything is tappable.
 *  - There is no HTML field and no markup field. Plain text only, rendered by
 *    `Text`. There is no path by which a stranger's words become a UI element.
 *  - [photoRefs] is empty until the user taps a load control. Loading a
 *    stranger's image from a stranger's URL leaks the reader's IP address,
 *    their network, and the fact that they opened this particular circle — to
 *    whoever put the URL there. The photo row is a labelled placeholder until
 *    the user asks for it.
 */
data class CirclePost(
    val id: Int,
    val circleId: Int,
    val authorName: String,
    val authorStanding: String,
    val kind: PostKind,
    val text: String? = null,
    /** The transcript of a voice note. Section 6.6 requires it before reporting. */
    val transcript: String? = null,
    val photoRefs: List<String> = emptyList(),
    val linkHostnames: List<String> = emptyList(),
    val createdAt: Instant,
    val commentCount: Int = 0,
    val likedByMe: Boolean = false,
    /** Section 15: held for a moderator. Not deleted. */
    val heldForReview: Boolean = false,
    /** I wrote this, so the report control is not offered on it. */
    val isMine: Boolean = false,
)

data class CircleComment(
    val id: Int,
    val postId: Int,
    val authorName: String,
    val text: String,
    val createdAt: Instant,
    val heldForReview: Boolean = false,
    val isMine: Boolean = false,
)

// ------------------------------------------------------------------- people --

/**
 * How far away, in words a person would use.
 *
 * ## Why this is an enum and not a number
 *
 * A latitude and longitude on a profile row is a home address, and this
 * population is not safe to have that on: a retired Section Officer's posting
 * station and two dates identify them to almost nobody, but a coordinate on a
 * social profile identifies them to everybody. Worse, a precise distance lets
 * somebody work *backwards* — 3 km from a known retirement colony, at a known
 * hour, is a location. Rounding to buckets and never showing metres removes the
 * inference rather than making it harder.
 *
 * "Nearby" is deliberately coarse and deliberately generous. A person who
 * needs to know somebody is within walking distance will ask; a person who
 * cannot see a number cannot triangulate.
 */
enum class CoarseDistance(val label: String) {
    SameBuilding("In your building"),
    SameArea("In your area"),
    SameCity("In your city"),
    SomewhereElse("Somewhere else"),
    /** The person did not say, or has not said, where they are. */
    NotSaid("Location not shared"),
}

/**
 * ## THE FIELD LIST OF THE PEOPLE SCREEN
 *
 * This is the screen most likely to leak, so the list is short, closed, and
 * written down here rather than left to whoever builds the row.
 *
 * Allowed, and this is all of it:
 *  - [displayName] — the name the user was asked to be called, "Sharma ji".
 *  - [departmentName] — the department the user typed at onboarding.
 *  - [cityName] — **city** granularity, from the city the user picked.
 *  - [distance] — the [CoarseDistance] bucket above. Never metres.
 *  - [standing] — "Verified pensioner" or "Not verified yet", from the tier.
 *  - [howYouKnow] — the connection, in words, so the user can judge it.
 *  - [lastSeenLabel] — a coarse "Active today". Never a clock time.
 *
 * Never present, on any row, in any code path:
 *  - **a phone number.** Section 1 says these users' phones are already the
 *    single most attacked surface in their lives; putting a number in a
 *    "colleagues near you" list hands the scammer the list. The server should
 *    not be sending one to this screen at all, and there is no field here for
 *    it to land in.
 *  - **a latitude or longitude**, or anything derived from one.
 *  - **a street, a house number, a landmark address, a society or colony
 *    name.** City is where this stops.
 *  - **joining year and retirement year**, despite the fact that the plan's
 *    matching function could happily compute a batch from them. Two dates plus
 *    a cadre is a fingerprint; the batch circle already gives the user that
 *    context without exposing the arithmetic.
 *  - **a "distance in km" affordance anywhere**, including a tooltip.
 */
data class PersonRow(
    val userId: Int,
    val displayName: String,
    val departmentName: String?,
    val cityName: String?,
    val distance: CoarseDistance,
    val standing: String,
    val howYouKnow: String?,
    val lastSeenLabel: String?,
)

// ------------------------------------------------------------------- events --

/**
 * RSVP is **set**, never toggled.
 *
 * The plan asks for an RSVP limit and this app's users are 60+ on phones where
 * a double tap is a fact of life. A toggle turns a second tap into a withdrawal
 * of a place somebody's daughter has already booked a taxi for; a set-state
 * turns a second tap into a no-op. See `CircleEventDetailViewModel.rsvp` for
 * what that does to the UI.
 */
enum class RsvpChoice(val code: String, val label: String, val verb: String) {
    Going("going", "You are going", "I'm going"),
    Maybe("maybe", "You might come", "I might come"),
    Cannot("cannot", "You are not coming", "I cannot come");

    /** What the user does the first time they answer. */
    val affirmative: Boolean get() = this != Cannot
}

data class CircleEventCard(
    val id: Int,
    val circleId: Int,
    val title: String,
    val startsAt: Instant,
    /** Free text the organiser typed. A venue name, never a person's address. */
    val place: String? = null,
    val cityName: String? = null,
    /** In paise, never a float. See `in.doosripari.feature.seva.Rupees`. */
    val costPaise: Long = 0L,
    val capacity: Int? = null,
    val going: Int = 0,
    val maybe: Int = 0,
    val myRsvp: RsvpChoice? = null,
) {
    val isFull: Boolean get() = capacity != null && going >= capacity
    val isFree: Boolean get() = costPaise <= 0L
}

// ------------------------------------------------------------- voice rooms --

/** Plan section 6.7: a host, up to eight speakers, listeners. */
enum class SeatRole(val code: String, val label: String) {
    Host("host", "Host"),
    Speaker("speaker", "Speaker"),
    Listener("listener", "Listening"),
}

/**
 * Somebody in a room.
 *
 * The record of a room is the list of who is in it, so this type carries a
 * display name and a role and nothing else. There is no lat/lng here either:
 * a voice room that shows a map of its speakers has built a location tracker
 * out of a feature whose whole point is company.
 */
data class RoomSeatView(
    val userId: Int,
    val displayName: String,
    val role: SeatRole,
    val handRaised: Boolean = false,
    val muted: Boolean = false,
    val isMe: Boolean = false,
)

/**
 * A Chai pe Charcha room.
 *
 * [isLive] comes from the server and is the only thing that may put the word
 * "live" on this screen. [sessionId] is null until the server has actually
 * started a session, and [speakers] is only meaningful once it is non-null.
 */
data class VoiceRoomCard(
    val id: Int,
    val title: String,
    val hostName: String,
    val circleName: String,
    val scheduledFor: Instant?,
    val isLive: Boolean,
    /** Null means no session has started — not an empty one. */
    val sessionId: Int? = null,
    val listenerCount: Int = 0,
    val speakerCount: Int = 0,
    val recordingAnnounced: Boolean = false,
    val mySeat: SeatRole? = null,
)

// ------------------------------------------------------------------ reports --

/** What is being reported. Section 15 requires this on every kind of content. */
enum class ReportTargetKind(val code: String, val whatYouSee: String) {
    Post("post", "post"),
    Comment("comment", "comment"),
    Profile("profile", "profile"),
    Room("room", "voice room"),
    Listing("listing", "listing"),
}

/**
 * Why somebody is reporting something.
 *
 * Every one of these names something a moderator can act on. There is no
 * "something else": an unactionable report teaches people that reporting does
 * nothing, and this audience is exactly the population that has been told by
 * somebody official that reporting "does not work".
 */
enum class ReportCause(val code: String, val label: String, val urgent: Boolean) {
    Abuse("abuse", "Abusive or threatening", true),
    HateSpeech("hate_speech", "Hate speech", true),
    Scam("scam", "A scam or fraud", true),
    PersonalInfo("personal_info", "Sharing my private information", true),
    Spam("spam", "Spam or repeated messages", false),
    WrongInformation("wrong_information", "Wrong information about pensions", false),
}

data class ReportTarget(
    val kind: ReportTargetKind,
    val id: Int,
    /** Used in the confirmation sentence so nobody reports the wrong thing. */
    val description: String,
)

// ------------------------------------------------------------------ helpers --

/**
 * "Tue 14 Oct, 8:00 am" in the phone's own locale.
 *
 * The plan's users read their events in their own script and their own 12-hour
 * or 24-hour convention. Formatting here rather than at the server keeps the
 * server out of the business of guessing.
 */
internal fun Instant.dayAndTimeLabel(): String {
    val local = toLocalDateTime(TimeZone.currentSystemDefault())
    val month = MONTHS[local.monthNumber - 1]
    val hour24 = local.hour
    val hour12 = when {
        hour24 == 0 -> 12
        hour24 > 12 -> hour24 - 12
        else -> hour24
    }
    val suffix = if (hour24 < 12) "am" else "pm"
    return "%s %d %s, %d:%02d %s".format(
        WEEKDAYS[local.dayOfWeek.number - 1],
        local.day,
        month,
        hour12,
        local.minute,
        suffix,
    )
}

/** "Tuesday". */
internal fun Instant.dayLabel(): String {
    val local = toLocalDateTime(TimeZone.currentSystemDefault())
    return "${WEEKDAYS[local.dayOfWeek.number - 1]} ${local.day} ${
        MONTHS[local.monthNumber - 1]
    }"
}

/**
 * A time to talk about a *past* thing with. Events, posts and comments all
 * need "3 days ago" and this is the only place that decides it, because three
 * slightly different hand-rolled versions of "3 days ago" is how a feed ends
 * up saying a post is from Tuesday on a Wednesday.
 */
internal fun Instant.agoLabel(now: Instant): String {
    val then = toLocalDateTime(TimeZone.currentSystemDefault())
    val today = now.toLocalDateTime(TimeZone.currentSystemDefault())
    val days = today.date - then.date
    return when {
        days <= 0 -> "Today, ${then.hour12Label()}"
        days == 1 -> "Yesterday, ${then.hour12Label()}"
        days < 7 -> "$days days ago"
        else -> then.dayLabel()
    }
}

private fun kotlinx.datetime.LocalTime.hour12Label(): String {
    val h = when {
        hour == 0 -> 12
        hour > 12 -> hour - 12
        else -> hour
    }
    val suffix = if (hour < 12) "am" else "pm"
    return "$h:%02d $suffix".format(minute)
}

private val WEEKDAYS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
private val MONTHS = listOf(
    "Jan", "Feb", "Mar", "Apr", "May", "Jun",
    "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
)
