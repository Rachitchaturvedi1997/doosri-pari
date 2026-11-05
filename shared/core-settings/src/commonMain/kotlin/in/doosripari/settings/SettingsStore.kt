package `in`.doosripari.settings

import `in`.doosripari.core.model.AppLanguage
import `in`.doosripari.core.model.TextScale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/**
 * The settings store, as a [Flow]. Plan section 4.3, section 6.16, section 14.
 *
 * ## Why the store is a port rather than a DataStore call
 *
 * Plan section 8.2 asks for "DataStore KMP", and this module depends on it. But
 * the DataStore-backed implementation belongs in `androidMain` (DataStore's
 * iOS support still needs the file path supplied by hand), and everything that
 * has to be *correct* — how a write is applied, how two screens racing on the
 * same setting resolve, that a consent write is atomic with its wording
 * version — is platform-independent and belongs in `commonMain` where it can be
 * tested.
 *
 * So [SettingsStore] is written against [SettingsStorage], and
 * [InMemorySettingsStorage] is a complete implementation of it. Swapping in the
 * DataStore adapter is a constructor argument, not a rewrite.
 *
 * **This module cannot currently reach DataStore on any platform.** The version
 * catalog alias `libs.datastore` points at `androidx.datastore:datastore-preferences`,
 * which is the Android artifact; the multiplatform one is
 * `datastore-preferences-core`. That is a build-file change and is flagged in
 * the module report rather than made here.
 *
 * ## Every read is a Flow, never a cached value
 *
 * The system text scale in particular cannot be cached: a user can change it in
 * Android Settings while this app is backgrounded, and a stored copy would be
 * wrong the moment they come back. So [systemTextScale] is a cold [Flow] the UI
 * re-subscribes to, and [AppSettings.composedTextScale] takes the value as an
 * argument rather than reading a field.
 */
interface SettingsStorage {
    /** Emits the current settings, and again on every change. */
    val changes: Flow<AppSettings>

    /**
     * Apply [transform] to the current value and persist the result.
     *
     * Read-modify-write rather than "set this field", because two screens can
     * be editing at once — a language picker and a consent sheet, say — and a
     * set-one-field API loses whichever write lands second. Implementations
     * must serialise the whole transform.
     */
    suspend fun update(transform: (AppSettings) -> AppSettings)
}

/**
 * The settings store.
 *
 * Every mutator goes through [update], so the Flow and the persisted value
 * cannot disagree. There is deliberately no "set the whole [AppSettings]"
 * method: a screen should be able to change the text scale without being able to
 * silently drop the consent record on the way past.
 */
class SettingsStore(
    private val storage: SettingsStorage,
    /**
     * Where the operating system's text scale comes from.
     *
     * Injected rather than looked up, so a screen can be given a fixed source in
     * a preview or a test without a running platform. Defaults to a source that
     * always says 1.0, which is the right answer for a JVM test and a harmless
     * placeholder everywhere else — the platform layer is expected to pass its
     * own.
     */
    private val systemTextScaleSource: SystemTextScaleSource = FixedSystemTextScaleSource(1f),
) {

    /** The current settings, and every future change. */
    val settings: Flow<AppSettings> get() = storage.changes

    /** One read, for a caller that genuinely needs a value now. */
    suspend fun current(): AppSettings = storage.changes.first()

    /**
     * The operating system's text scale, cold.
     *
     * Android: `Resources.configuration.fontScale`. iOS:
     * `UIFontMetrics` / `preferredContentSizeCategory`. Must not be cached in
     * this module — see the file KDoc.
     */
    val systemTextScale: Flow<Float> get() = systemTextScaleSource.flow

    /**
     * The scale type should actually be rendered at: this app's setting composed
     * with the operating system's.
     *
     * The composition [AppSettings.composedTextScale] does, exposed here so that
     * a screen has one obvious place to ask and cannot accidentally read
     * [AppSettings.textScale] on its own and drop the user's system setting.
     */
    val composedTextScale: Flow<Float> = combine(settings, systemTextScale) { current, system ->
        current.composedTextScale(system)
    }

    // ------------------------------------------------------------- language --

    /** Plan section 3.5's ten languages. */
    suspend fun setLanguage(language: AppLanguage) = update { it.copy(language = language) }

    /**
     * Adopt the device language on first launch.
     *
     * Returns whether it changed anything, so a caller can decide whether to
     * navigate to the language screen. It never overwrites a language the user
     * has already chosen — that is the difference between "default" and
     * "selected", and a pensioner who picked Marathi over Hindi to read their
     * own notices should not have it switched back because they reinstalled.
     */
    suspend fun adoptDeviceLanguageIfUnset(deviceLanguage: AppLanguage): Boolean {
        var changed = false
        update { current ->
            if (current.language != AppLanguage.default) {
                current
            } else {
                changed = deviceLanguage != current.language
                current.copy(language = deviceLanguage)
            }
        }
        return changed
    }

    // ------------------------------------------------------------ text scale --

    /**
     * The in-app text scale, which is a multiplier *on top of* the system's.
     *
     * [TextScale.System] — the default — means "leave the system alone", and the
     * system value then passes through unchanged. Choosing [TextScale.Largest]
     * multiplies the system scale by 1.3; it does not replace it with 1.3. See
     * [AppSettings] for why that distinction is the point of the whole feature.
     */
    suspend fun setTextScale(scale: TextScale) = update { it.copy(textScale = scale) }

    // ---------------------------------------------------------- check-in window --

    suspend fun setCheckInWindow(window: CheckInWindow) =
        update { it.copy(checkInWindow = window) }

    /** Plan section 6.3: travel or hospital, no reminders, no calls, no alerts. */
    suspend fun pauseCheckIns(untilEpochSeconds: Long) =
        update { it.copy(checkInWindow = it.checkInWindow.copy(pausedUntilEpochSeconds = untilEpochSeconds)) }

    suspend fun resumeCheckIns() =
        update { it.copy(checkInWindow = it.checkInWindow.copy(pausedUntilEpochSeconds = null)) }

    // --------------------------------------------------------- passive signals --

    /** Plan section 6.3: off by default, and every source named separately. */
    suspend fun setPassiveSignals(passive: PassiveSignals) =
        update { it.copy(passive = passive) }

    suspend fun setPassiveEnabled(enabled: Boolean) =
        update { it.copy(passive = it.passive.copy(enabled = enabled)) }

    // ------------------------------------------------------- location sharing --

    suspend fun setLocationSharing(location: LocationSharing) =
        update { it.copy(location = location) }

    // ---------------------------------------------------------- notifications --

    suspend fun setNotifications(notifications: NotificationPermissions) =
        update { it.copy(notifications = notifications) }

    /** Record what the platform reported. The app never guesses this. */
    suspend fun setPushPermissionState(state: PermissionState) =
        update { it.copy(notifications = it.notifications.copy(push = state)) }

    // ---------------------------------------------------------------- consent --

    /**
     * Grant a capability, at the wording version the user actually saw.
     *
     * A withdrawal is not a grant and goes through [withdrawConsent]; keeping
     * them apart is what lets the record say when each happened. See
     * `Consent.kt` for why the wording version is part of this and not a UI
     * detail.
     */
    suspend fun grantConsent(
        capability: GuardianCapability,
        wordingVersion: Int = ConsentSet.wordingVersionFor(capability),
        now: Instant = Clock.System.now(),
    ) = update { current ->
        current.copy(consents = current.consents + ConsentGrant(
            capability = capability,
            wordingVersion = wordingVersion,
            grantedAt = now,
        ))
    }

    /**
     * Withdraw a capability. Plan section 14 requires withdrawal in Settings.
     *
     * Records the time rather than deleting the row — see `Consent.kt`.
     */
    suspend fun withdrawConsent(
        capability: GuardianCapability,
        now: Instant = Clock.System.now(),
    ) = update { current ->
        val existing = current.consents.grants[capability]
            ?: return@update current
        current.copy(consents = current.consents.copy(grants = current.consents.grants + (
            existing.copy(withdrawnAt = now)
            )))
    }

    /** Capabilities the app must ask about before it does anything. */
    suspend fun outstandingConsents(): List<GuardianCapability> =
        ConsentRegistry.outstandingFor(current())

    /** Whether the consent sheet has to be shown first. */
    suspend fun hasOutstandingConsent(): Boolean = outstandingConsents().isNotEmpty()

    private suspend fun update(transform: (AppSettings) -> AppSettings) =
        storage.update(transform)
}

/**
 * Where the operating system's text scale comes from.
 *
 * A port for the same reason as [SettingsStorage]: the value is a platform API
 * (`Configuration.fontScale` on Android, `UIFontMetrics.default` on iOS) and
 * the shared code needs something to call. [DefaultSystemTextScaleSource] keeps
 * a value the platform can push into.
 */
interface SystemTextScaleSource {
    val flow: Flow<Float>
}

/**
 * A text scale the platform writes into.
 *
 * Android holds a `Configuration` callback and pushes a new value whenever the
 * system font size changes; iOS observes
 * `UIFont.preferredContentSizeCategoryDidChangeNotification` and pushes the
 * equivalent scale. Neither is a poll: the change arrives as a callback, and
 * this is where it lands.
 */
class DefaultSystemTextScaleSource(initial: Float = 1f) : SystemTextScaleSource {

    private val state = MutableStateFlow(initial)

    override val flow: Flow<Float> = state.asStateFlow()

    /** Called by the platform layer. [scale] is Android's `fontScale`. */
    fun update(scale: Float) {
        state.value = scale
    }
}

/**
 * A system text scale that never moves.
 *
 * The honest reading of "this phone is at its default size". A `MutableStateFlow`
 * of the real value starts at Android's `1.0` and is only corrected once the
 * first `Configuration` callback lands, so this matches what the platform has
 * told us at the moment of construction rather than inventing a different
 * default.
 */
class FixedSystemTextScaleSource(scale: Float = 1f) : SystemTextScaleSource {
    override val flow: Flow<Float> = flowOf(scale)
}

/**
 * A complete [SettingsStorage] that keeps the value in memory.
 *
 * Used by the tests, and by any target that has not yet been given a real
 * backing store. Deliberately not called a fake: it is a working implementation
 * of the same contract, and the serialisation of concurrent writes below is
 * copied verbatim into the DataStore adapter.
 *
 * The [Mutex] is the part worth stealing. Two `update` calls that read, decide
 * and write without one can interleave as read-read-write-write, and the second
 * write is computed from a value the first has already replaced — which is how
 * a language change loses a consent grant that was written in between.
 */
class InMemorySettingsStorage(
    initial: AppSettings = AppSettings(),
) : SettingsStorage {

    private val mutex = Mutex()
    private val state = MutableStateFlow(initial)

    override val changes: Flow<AppSettings> = state.asStateFlow()

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        mutex.withLock {
            state.update(transform)
        }
    }

    /** The current value, for a test that wants to assert without collecting. */
    fun snapshot(): AppSettings = state.value
}
