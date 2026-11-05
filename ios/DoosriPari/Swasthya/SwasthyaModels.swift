// UNTESTED — never compiled. See ios/README.md.
//
// Plan section 6.10 (Health) and plan section 14 (Privacy), for the health
// half. The wire names are not free-hand: the server owns them and a rename on
// either side should fail a test rather than quietly show a pensioner a stale
// medicine. Keys are spelled out rather than left to
// `.convertFromSnakeCase`, because a strategy change turns a rename into a
// field that decodes as nil forever.
//
// THE BOUNDARY OF THIS MODULE, in one place so nobody has to go looking:
//
//   Swasthya records and shows. It NEVER diagnoses, never interprets a blood
//   pressure reading, never suggests a dose change. Plan 6.10 says "usual
//   range" references and "talk to your doctor", never a diagnosis, and plan
//   14 says general wellness information only. Every type below obeys that by
//   construction: there is no "is this blood pressure high" here, only "is
//   this reading plausible enough to have been typed in correctly".

import Foundation

// MARK: - Consent

/// Plan section 14: health data is a SEPARATE named consent, withdrawable on
/// its own. It is not folded into "location" and not implied by signing in.
/// A person who says yes to their pension questions has not said yes to their
/// blood pressure.
enum HealthConsentState: String, Codable {
    case notGranted
    case granted

    var isGranted: Bool { self == .granted }
}

// MARK: - Medicines

/// Plan 6.10: "dose, times, days and food timing".
enum FoodTiming: String, Codable, CaseIterable {
    case beforeFood
    case withFood
    case afterFood
    case anyTime

    var displayName: String {
        switch self {
        case .beforeFood: return SwasthyaText.t("swa_food_before", "Before food")
        case .withFood: return SwasthyaText.t("swa_food_with", "With food")
        case .afterFood: return SwasthyaText.t("swa_food_after", "After food")
        case .anyTime: return SwasthyaText.t("swa_food_any", "Any time")
        }
    }
}

/// One time of day a dose is due. Local wall-clock time, never UTC: 8:00 in
/// Gorakhpur is 8:00 in Gorakhpur, and a medicine reminder that fires at the
/// wrong hour is a medicine not taken.
struct DoseTime: Codable, Hashable, Comparable {
    let hour: Int
    let minute: Int

    var clockLabel: String {
        String(format: "%02d:%02d", hour, minute)
    }

    /// Sorted lists read morning to night on every screen.
    static func < (lhs: DoseTime, rhs: DoseTime) -> Bool {
        (lhs.hour, lhs.minute) < (rhs.hour, rhs.minute)
    }
}

/// One medicine the pensioner takes.
///
/// `shareMissesWithGuardian` is the single most consequential field in this
/// module. See the comment where it is edited — the difference between family
/// support and family surveillance is exactly this toggle.
struct Medicine: Codable, Identifiable, Equatable {
    let id: String
    var name: String

    /// Free text, because "1 tablet", "half tablet" and "5 ml" are all things
    /// people say into a microphone and none of them parse into a number
    /// without losing something.
    var dose: String

    var foodTiming: FoodTiming
    var times: [DoseTime]

    /// Calendar weekday numbers, 1 (Sunday) through 7 (Saturday) as
    /// `Calendar` spells them. Empty means every day.
    var days: [Int]

    /// A photo of the strip, so a medicine named by voice can still be checked
    /// against the packet. Path is a local file reference; the picture is on
    /// the phone like everything else here.
    var photoStripPath: String?

    /// Plan 6.10: "the guardian is told after 2 misses in a row, **if
    /// allowed**". This is that "if allowed", per medicine, not per account.
    var shareMissesWithGuardian: Bool

    /// Tablets (or doses) left. Drives the plan's refill alert at 5 days of
    /// stock; nil means we were not told.
    var dosesRemaining: Int?

    var displayName: String {
        name.isEmpty
            ? SwasthyaText.t("swa_medicine_unnamed", "Medicine without a name")
            : name
    }

    /// The refill alert, plan 6.10: "at 5 days of stock". Days are counted
    /// from doses per day so the number means the same thing for a once-a-day
    /// tablet and a three-times-a-day one.
    var isRefillDueSoon: Bool {
        guard let remaining = dosesRemaining, !times.isEmpty else { return false }
        let dosesPerDay = max(times.count, 1)
        let daysLeft = Double(remaining) / Double(dosesPerDay)
        return daysLeft <= 5
    }

    /// Plan 6.10 says days, and an empty list means "every day".
    func isDue(on weekday: Int) -> Bool {
        days.isEmpty || days.contains(weekday)
    }
}

// MARK: - The dose log

/// What the person told us, in their words: "Taken", "Skip", "Later (15 min)".
/// Plan 6.10 gives exactly these three.
///
/// There is deliberately no `missed` case here. "Missed" is not something a
/// person says about themselves; it is what the app infers when nobody ever
/// acknowledged a dose. See `DoseStatus`.
enum DoseAcknowledgement: String, Codable {
    case taken
    case skipped
    /// "Later (15 min)" — the dose is deferred, not refused and not forgotten.
    case postponed

    var displayName: String {
        switch self {
        case .taken: return SwasthyaText.t("swa_dose_taken", "Taken")
        case .skipped: return SwasthyaText.t("swa_dose_skipped", "Skipped")
        case .postponed: return SwasthyaText.t("swa_dose_later", "Later (15 min)")
        }
    }
}

/// What a dose looks like on screen. Four states, three of them heard and one
/// inferred.
enum DoseStatus {
    case upcoming
    case taken
    case skipped
    /// Nobody ever acknowledged it. See the comment in
    /// `MedicineScheduleView` where this renders.
    case missed

    var displayName: String {
        switch self {
        case .upcoming: return SwasthyaText.t("swa_status_upcoming", "Due")
        case .taken: return SwasthyaText.t("swa_status_taken", "Taken")
        case .skipped: return SwasthyaText.t("swa_status_skipped", "Skipped")
        case .missed: return SwasthyaText.t("swa_status_missed", "Missed")
        }
    }
}

/// One scheduled dose and whatever happened to it.
struct DoseLogEntry: Codable, Identifiable, Equatable {
    let id: String
    let medicineID: String

    /// Epoch seconds of when the dose was due.
    let scheduledAt: Double

    var acknowledged: DoseAcknowledgement?
    var acknowledgedAt: Double?

    /// A dose nobody acknowledged is MISSED once its moment has passed by
    /// more than the grace window below.
    ///
    /// The premise of this app is that a person who stopped taking their
    /// tablets did not decide to skip them. Deciding is what `skipped` is for,
    /// and it takes a tap. Silence is not a decision, and the app must not
    /// read it as one anywhere — but it also must not call a dose "due" at
    /// midnight, so silence eventually becomes `missed`, which is a report on
    /// the app's own failure to reach them as much as on anything else.
    ///
    /// The grace window is two hours: long enough that "Later (15 min)" twice
    /// in a row is still within it, short enough that an evening dose is not
    /// still "Due" at bedtime.
    static let graceSeconds: Double = 2 * 60 * 60

    func status(now: Date) -> DoseStatus {
        switch acknowledged {
        case .taken: return .taken
        case .skipped: return .skipped
        case .postponed:
            // Postponed keeps waiting; it does not silently age into missed
            // before the new time has even come round.
            return .upcoming
        case nil:
            let deadline = scheduledAt + Self.graceSeconds
            return now.timeIntervalSince1970 > deadline ? .missed : .upcoming
        }
    }
}

// MARK: - Vitals

/// Plan 6.10: BP, sugar, weight and pulse.
enum VitalKind: String, Codable, CaseIterable {
    case bloodPressure
    case sugar
    case weight
    case pulse

    var displayName: String {
        switch self {
        case .bloodPressure: return SwasthyaText.t("swa_vital_bp", "Blood pressure")
        case .sugar: return SwasthyaText.t("swa_vital_sugar", "Sugar")
        case .weight: return SwasthyaText.t("swa_vital_weight", "Weight")
        case .pulse: return SwasthyaText.t("swa_vital_pulse", "Pulse")
        }
    }

    var unit: String {
        switch self {
        case .bloodPressure: return SwasthyaText.t("swa_unit_bp", "mmHg")
        case .sugar: return SwasthyaText.t("swa_unit_sugar", "mg/dL")
        case .weight: return SwasthyaText.t("swa_unit_weight", "kg")
        case .pulse: return SwasthyaText.t("swa_unit_pulse", "per minute")
        }
    }
}

/// One measurement, exactly as entered.
struct VitalReading: Codable, Identifiable, Equatable {
    let id: String
    let kind: VitalKind

    /// Blood pressure only: the two numbers of a reading, kept as two numbers
    /// and never averaged, summed or otherwise turned into one.
    var systolic: Int?
    var diastolic: Int?

    /// Sugar, weight and pulse.
    var value: Double?

    /// Epoch seconds.
    let takenAt: Double

    var note: String?

    /// Bounds for "could this reading physically have come from a machine" —
    /// NOT "is this healthy".
    ///
    /// THIS IS WHERE THE TEMPTATION LIVES. 180/110 sits there asking to be
    /// labelled "high", and the moment this file says so the app has made a
    /// diagnosis, which plan 6.10 and plan 14 both forbid and which a
    /// pensioner may act on instead of calling their doctor. These bounds
    /// exist so that a typo — 1800 instead of 180, a weight in pounds entered
    /// into a kilogram box — gets flagged **for the doctor** and otherwise
    /// left alone. A plausible reading is shown exactly as typed and
    /// explained by nobody except the human who ordered the test.
    func isOutsidePlausibleRange() -> Bool {
        switch kind {
        case .bloodPressure:
            guard let s = systolic, let d = diastolic else { return true }
            return !(50...260).contains(s) || !(30...160).contains(d) || d >= s
        case .sugar:
            guard let v = value else { return true }
            return !(20...600).contains(v)
        case .weight:
            guard let v = value else { return true }
            return !(20...250).contains(v)
        case .pulse:
            guard let v = value else { return true }
            return !(25...220).contains(v)
        }
    }
}

// MARK: - Reminders

/// Plan 6.10's reminders, plus the refill alert.
struct HealthReminder: Codable, Identifiable, Equatable {
    enum Kind: String, Codable {
        case medicineDose
        case refill
        case vitalsCheck
    }

    let id: String
    let kind: Kind
    var title: String
    var detail: String

    /// Epoch seconds.
    let fireAt: Double
    var isDone: Bool
}

// MARK: - Facility directory

/// Plan 6.10: CGHS/ECHS finder, "from official lists refreshed monthly, each
/// showing 'last updated'".
enum HealthScheme: String, Codable, CaseIterable {
    case cghs
    case echs

    var displayName: String {
        switch self {
        case .cghs: return SwasthyaText.t("swa_scheme_cghs", "CGHS")
        case .echs: return SwasthyaText.t("swa_scheme_echs", "ECHS")
        }
    }
}

struct HealthFacility: Codable, Identifiable, Equatable {
    let id: String
    var name: String
    var scheme: HealthScheme
    var city: String
    var address: String

    /// The phone number as dialled. Shown as text with a call button, never as
    /// a bare number a person has to copy by hand.
    var phone: String

    /// Shown on every row. A directory that cannot say when it was last
    /// refreshed is a directory that sends somebody across a city to a clinic
    /// that moved.
    let lastUpdated: String
}