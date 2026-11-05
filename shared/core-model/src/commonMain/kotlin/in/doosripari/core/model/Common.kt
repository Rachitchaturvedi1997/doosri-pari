package `in`.doosripari.core.model

import kotlinx.datetime.Instant

/**
 * Primitives every feature shares.
 *
 * These are DOMAIN types, not wire DTOs. Nothing here is `@Serializable`, and
 * that is deliberate: the serializable mirror of each one lives in each
 * platform's data layer, where it carries `@SerialName("snake_case")`. A server
 * field rename is then a compile error in one small file rather than a silent
 * behaviour change spread across every screen. The existing check-in slice
 * works exactly this way — see `android/.../data/CheckInRepository.kt`.
 *
 * This module has no dependencies beyond coroutines and kotlinx-datetime. A
 * module that cannot break, and that thirteen others import, is worth keeping
 * deliberately boring.
 */

/** A point on the earth. Only ever read at the moment of a check-in. */
data class GeoPoint(
    val lat: Double,
    val lng: Double,
    /** Metres, as reported by the device. Null when the platform gave none. */
    val accuracyM: Double? = null,
) {
    init {
        require(lat in -90.0..90.0) { "lat out of range: $lat" }
        require(lng in -180.0..180.0) { "lng out of range: $lng" }
    }
}

/**
 * The one error shape the API uses. Plan section 11 fixes it as
 * `{"detail": "...", "errors": [{"field", "msg"}]}` so a client never has to
 * guess which of three shapes it got.
 *
 * [fieldErrors] is empty for most failures and carries per-field problems for
 * a form the user can actually correct.
 */
data class ApiError(
    val detail: String,
    val fieldErrors: List<FieldError> = emptyList(),
    /** HTTP status, kept so the UI can distinguish "try again" from "no". */
    val status: Int = 0,
) {
    val isRetryable: Boolean get() = status == 0 || status in 500..599 || status == 429

    /** The message to show a 60+ user, if the server gave a usable one. */
    val displayMessage: String
        get() = if (detail.isBlank()) "Something went wrong. Please try again." else detail
}

data class FieldError(val field: String, val message: String)

/**
 * Cursor pagination. Plan section 6.6's feed pages with a cursor rather than a
 * page number, so a post arriving while the user scrolls cannot make them skip
 * a row.
 */
data class Page<T>(
    val items: List<T>,
    val nextCursor: String? = null,
    val hasMore: Boolean get() = nextCursor != null,
) {
    companion object {
        /** A page that is definitely the last one. */
        fun <T> last(items: List<T> = emptyList()): Page<T> = Page(items, null)
    }
}

/**
 * Time is stored on the wire as epoch seconds, matching `python/models.py`.
 * This type alias exists so that choice is stated once. A check-in window is a
 * LOCAL window, so every comparison a feature makes against one must carry a
 * timezone — see `CheckInSettings` in `feature-checkin`.
 */
typealias EpochSeconds = Double

fun EpochSeconds.toInstant(): Instant = Instant.fromEpochSeconds(this.toLong())

fun Instant.toEpochSeconds(): EpochSeconds = epochSeconds.toDouble()

/**
 * The ten languages of plan section 3.5, in the order they are listed there.
 *
 * Hindi first after English because it is the one every other is measured
 * against: several of these scripts do not have a settled Devanagari
 * equivalent for a banking term, and a translation that reads oddly in Tamil is
 * better than one that reads wrongly.
 */
enum class AppLanguage(val code: String, val endonym: String) {
    English("en", "English"),
    Hindi("hi", "हिन्दी"),
    Marathi("mr", "मराठी"),
    Kannada("kn", "ಕನ್ನಡ"),
    Tamil("ta", "தமிழ்"),
    Telugu("te", "తెలుగు"),
    Bengali("bn", "বাংলা"),
    Gujarati("gu", "ગુજરાતી"),
    Malayalam("ml", "മലയാളം"),
    Punjabi("pa", "ਪੰਜਾਬੀ");

    companion object {
        val default: AppLanguage get() = English

        /**
         * Match a device locale, falling back to English.
         *
         * Android reports "hi-IN" and iOS "hi_IN", and a user whose phone is
         * set to a language this app has not been translated into should see
         * English rather than a half-translated screen.
         */
        fun fromTag(tag: String?): AppLanguage {
            if (tag.isNullOrBlank()) return default
            val primary = tag.replace('_', '-').substringBefore('-').lowercase()
            return entries.firstOrNull { it.code == primary } ?: default
        }
    }
}

/**
 * In-app text scale, on top of whatever the system size is. Plan section 4.3:
 * the system setting and this one both apply, so a 60+ user can go as large as
 * they like without fighting the OS.
 */
enum class TextScale(val multiplier: Float) {
    System(1.0f),
    Large(1.15f),
    Largest(1.3f);

    companion object {
        val default: TextScale get() = System

        fun fromMultiplier(value: Float): TextScale =
            entries.minByOrNull { kotlin.math.abs(it.multiplier - value) } ?: System
    }
}

/**
 * What the app is doing that the user should know about right now.
 *
 * Everything that blocks the UI goes through this rather than through a bare
 * exception, because a 60+ user who sees a spinner with no explanation will
 * assume the worst.
 */
sealed interface LoadState<out T> {
    data object Loading : LoadState<Nothing>

    data class Loaded<T>(val value: T) : LoadState<T>

    data class Failed(val error: ApiError) : LoadState<Nothing>

    val valueOrNull: T? get() = (this as? Loaded)?.value
    val isLoading: Boolean get() = this is Loading
}
