package `in`.doosripari.feature.health

import `in`.doosripari.core.model.Facility
import `in`.doosripari.core.model.GeoPoint
import kotlinx.datetime.Instant
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * CGHS / ECHS facility search. Plan section 6.10: "from official lists refreshed
 * monthly, each showing 'last updated'".
 *
 * ## What this finder is not
 *
 * It is a list of empanelled hospitals with a distance, a telephone number and
 * what they accept. It is not a triage tool and it does not know who is open.
 * The distinction matters more here than anywhere else in the app, because the
 * person using it is ill enough to be looking: plan section 14 forbids diagnosis
 * and plan section 21's "health" row says the same. So the list never says
 * "nearest emergency care" and never reorders itself by how busy a hospital is,
 * because neither of those is knowable from a monthly list. It says what the
 * official list said, how old that list is, and how far away it is.
 *
 * ## Where the distance comes from
 *
 * [Facility.distanceKm] wins when the server supplied it, because the server
 * geocoded the address against the same gazetteer the search was run against.
 * A great-circle estimate from [Facility.location] is only the fallback, and it
 * is flagged as approximate when it is used — 3 km out on a map is a different
 * hospital.
 */
object FacilityFinder {

    /**
     * Default radius. Ten kilometres is about "can I get there by auto".
     *
     * Beyond that the list stops being useful: a CGHS hospital forty kilometres
     * away in a town with three of them is not a choice, and offering it as one
     * wastes the two taps somebody has left before they give up.
     */
    val DEFAULT_RADIUS_KM: Double = 10.0

    /**
     * A list older than this says so on every row.
     *
     * A month, because the plan says the lists are refreshed monthly, and a
     * "last updated" label that never turns amber teaches people to stop
     * reading it.
     */
    const val STALE_AFTER_DAYS: Long = 45

    /**
     * Search a snapshot. Pure: the caller has already fetched the list.
     *
     * @param snapshot the whole loaded list, so the "last updated" line and the
     *   staleness verdict travel with the results rather than being looked up
     *   separately and possibly disagreeing.
     * @param now only used to decide whether the list is old enough to warn
     *   about. Passed in rather than read, because the module is otherwise pure
     *   and a search that reads the clock is a search that cannot be tested.
     */
    fun search(
        snapshot: FacilitySnapshot,
        criteria: FacilityQuery,
        now: Instant,
    ): FacilityResults {
        val wanted = criteria.scheme
        val matched = snapshot.facilities.filter { facility ->
            val schemeOk = wanted == null || facility.schemes.any { it.equals(wanted.code, ignoreCase = true) }
            val textOk = criteria.query.isNullOrBlank() ||
                facility.name.contains(criteria.query, ignoreCase = true) ||
                facility.address?.contains(criteria.query, ignoreCase = true) == true
            schemeOk && textOk
        }

        val results = matched
            .map { facility -> toResult(facility, criteria.origin) }
            .filter { result ->
                val distance = result.distanceKm
                distance == null || criteria.withinRadius(distance)
            }
            .sortedWith(
                compareBy(
                    { it.distanceKm ?: Double.MAX_VALUE },
                    { it.facility.name.lowercase() },
                ),
            )

        return FacilityResults(
            snapshot = snapshot,
            results = results.take(criteria.limit),
            totalMatched = results.size,
            isStale = snapshot.isStaleAt(now),
        )
    }

    /**
     * One row of the list, with the distance the screen will print.
     *
     * [distanceIsApproximate] is true when the figure came from straight-line
     * geometry rather than from the server's own geocoding, and the UI is
     * expected to soften the wording accordingly. A user told "3 km" who arrives
     * at 5 km concludes the app is wrong about everything, including the list
     * it printed the distance from.
     */
    fun toResult(facility: Facility, origin: GeoPoint?): FacilityResult {
        val supplied = facility.distanceKm
        val computed = if (origin == null) null else greatCircleKm(origin, facility.location)
        return FacilityResult(
            facility = facility,
            distanceKm = supplied ?: computed,
            distanceIsApproximate = supplied == null && computed != null,
            schemes = facility.schemes.mapNotNull { FacilityScheme.fromCode(it) },
        )
    }

    /**
     * Great-circle distance in kilometres. Used only when the server did not
     * supply one.
     *
     * A sphere, not a road. There is no routing engine in this app and adding
     * one to decide which hospital to show a sick pensioner would be both slow
     * on a ₹8,000 phone and a claim about roads this code has never seen.
     */
    fun greatCircleKm(from: GeoPoint, to: GeoPoint): Double {
        val dLat = (to.lat - from.lat) * DEGREES_TO_RADIANS
        val dLng = (to.lng - from.lng) * DEGREES_TO_RADIANS
        val lat1 = from.lat * DEGREES_TO_RADIANS
        val lat2 = to.lat * DEGREES_TO_RADIANS
        val h = sin(dLat / 2) * sin(dLat / 2) +
            sin(dLng / 2) * sin(dLng / 2) * cos(lat1) * cos(lat2)
        return 2 * EARTH_RADIUS_KM * atan2(sqrt(h), sqrt(1 - h))
    }

    /** Mean earth radius. The same figure every distance calculation in the app uses. */
    const val EARTH_RADIUS_KM: Double = 6371.0088

    /** `Math.toRadians` is JVM-only, and this module compiles for iOS as well. */
    private const val DEGREES_TO_RADIANS: Double = PI / 180.0
}

/**
 * A scheme a facility may accept.
 *
 * The strings that come off the wire are whatever the official list said, which
 * is why [fromCode] is lenient about case and spacing. The list is refreshed
 * from government sources and "CGHS", "cghs" and "CGHS " all turn up in it.
 */
enum class FacilityScheme(val code: String) {
    Cghs("CGHS"),
    Echs("ECHS"),
    Ayushman("Ayushman PMJ"),
    Esic("ESIC"),
    /** A state scheme. Which one is in [Facility.schemes] as free text. */
    State("State");

    companion object {
        /** Lenient on purpose. See the KDoc. */
        fun fromCode(code: String?): FacilityScheme? {
            val key = code?.trim() ?: return null
            return entries.firstOrNull { it.code.equals(key, ignoreCase = true) }
        }
    }
}

/**
 * The whole loaded list, and when it was loaded.
 *
 * Plan section 6.10 says every entry shows "last updated", and core-model's
 * `Facility` has no field for it, so it lives on the snapshot. One date for the
 * whole list rather than one per row, because the list is fetched as a unit
 * from a monthly job and a per-row date would be a fiction. That is a
 * core-model gap, raised as a finding.
 */
data class FacilitySnapshot(
    val facilities: List<Facility>,
    /** The date the monthly job loaded this list. Not a per-facility date. */
    val loadedAt: Instant,
    /** Which state or city the list is scoped to, if it is scoped at all. */
    val region: String? = null,
    /** The official source, for the "where did this come from" line. */
    val source: String? = null,
) {
    val isEmpty: Boolean get() = facilities.isEmpty()

    /**
     * Whether the list is old enough that the screen should say so.
     *
     * Staleness is not a reason to hide anything. A list that is two months old
     * is still the best information available, and "this list was last updated on
     * 3 August" is honest where a confident green tick would not be.
     */
    fun isStaleAt(now: Instant, staleAfterDays: Long = FacilityFinder.STALE_AFTER_DAYS): Boolean =
        now.epochSeconds - loadedAt.epochSeconds > staleAfterDays * 86_400L
}

/** What the user asked for. Every field optional; a blank finder shows everything. */
data class FacilityQuery(
    val origin: GeoPoint? = null,
    val scheme: FacilityScheme? = null,
    /** Free text over the name and the address. Never over the scheme. */
    val query: String? = null,
    val radiusKm: Double? = null,
    val limit: Int = DEFAULT_LIMIT,
) {
    private fun withinRadius(distance: Double): Boolean =
        radiusKm == null || distance <= radiusKm

    companion object {
        /**
         * Twenty. A list of sixty hospitals is not a choice, and scrolling past
         * the fortieth is how somebody concludes the app has not found the right
         * one when it found it at number three.
         */
        const val DEFAULT_LIMIT: Int = 20
        const val DEFAULT_RADIUS_KM: Double = FacilityFinder.DEFAULT_RADIUS_KM
    }
}

/**
 * One hospital, ready to render.
 *
 * [schemes] is the parsed form of [Facility.schemes] so that a row can say
 * "CGHS · ECHS" without every screen re-parsing free text.
 */
data class FacilityResult(
    val facility: Facility,
    val distanceKm: Double?,
    val distanceIsApproximate: Boolean,
    val schemes: List<FacilityScheme>,
) {
    val acceptsCghs: Boolean get() = schemes.contains(FacilityScheme.Cghs)
    val acceptsEchs: Boolean get() = schemes.contains(FacilityScheme.Echs)

    /**
     * Whether the row can offer a telephone call.
     *
     * A missing number is not a reason to hide the hospital — it is the reason
     * to show the address instead, because the user is still entitled to know it
     * is there.
     */
    val canCall: Boolean get() = !facility.phone.isNullOrBlank()
}

/** The answer to a search, with the list's own age attached. */
data class FacilityResults(
    val snapshot: FacilitySnapshot,
    val results: List<FacilityResult>,
    /** How many matched before [FacilityQuery.limit] was applied. */
    val totalMatched: Int,
    val isStale: Boolean,
) {
    val isEmpty: Boolean get() = results.isEmpty()

    /** Whether the list was cut short, so the screen can offer "show more". */
    val isTruncated: Boolean get() = results.size < totalMatched

    /**
     * The nearest facility that accepts a scheme the user asked for, or null.
     *
     * Exposed as a single answer because the SOS screen needs one row, not a
     * list, and building it here keeps "nearest" honest: nearest by the same
     * distance the list is sorted by, including the supplied-versus-computed
     * distinction, rather than a second ranking computed at the call site.
     */
    fun nearest(): FacilityResult? = results.firstOrNull()
}
