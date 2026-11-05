package `in`.doosripari.settings

import `in`.doosripari.core.model.AppLanguage
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The store's own rules: what it does with writes, and what it refuses to lose. */
class SettingsStoreTest {

    @Test
    fun `a fresh store has the plan's defaults`() = runTest {
        val settings = SettingsStore(InMemorySettingsStorage()).current()

        assertEquals(AppLanguage.default, settings.language)
        assertEquals(9 * 60, settings.checkInWindow.startMinuteOfDay, "plan section 6.3: a 09:00 window")
        assertEquals(180, settings.checkInWindow.minutes)
        assertFalse(settings.passive.enabled, "plan section 6.3: passive signals are off by default")
        assertEquals(200, settings.passive.stepThreshold)
        assertFalse(settings.location.rememberLastKnown)
        assertFalse(settings.location.shareWithGuardiansOnMiss)
        assertTrue(settings.hasOutstandingConsent)
    }

    @Test
    fun `the default language is adopted on first launch but never overwritten`() = runTest {
        val storage = InMemorySettingsStorage()
        val store = SettingsStore(storage)

        assertTrue(store.adoptDeviceLanguageIfUnset(AppLanguage.Marathi))

        // The user then picks something else deliberately.
        store.setLanguage(AppLanguage.Tamil)

        // A second launch must not undo that: "default" and "chosen" are
        // different states, and a pensioner who picked a language to read their
        // own notices should not be switched back by a reinstall.
        assertFalse(store.adoptDeviceLanguageIfUnset(AppLanguage.Hindi))
        assertEquals(AppLanguage.Tamil, store.current().language)
    }

    @Test
    fun `pausing check-ins suppresses the window and resuming restores it`() = runTest {
        val store = SettingsStore(InMemorySettingsStorage())
        val now = 1_700_000_000L

        assertFalse(store.current().checkInWindow.isPausedAt(now))

        store.pauseCheckIns(untilEpochSeconds = now + 86_400)
        assertTrue(store.current().checkInWindow.isPausedAt(now), "travel, hospital, no reminders")
        assertFalse(store.current().checkInWindow.isPausedAt(now + 172_800), "and then it lapses by itself")

        store.pauseCheckIns(untilEpochSeconds = now + 86_400)
        store.resumeCheckIns()
        assertFalse(store.current().checkInWindow.isPausedAt(now))
    }

    @Test
    fun `a passive master switch on with no source selected still records nothing`() = runTest {
        // A half-finished settings screen must not be able to turn the phone
        // into something that checks the user in by accident.
        val store = SettingsStore(InMemorySettingsStorage())
        store.setPassiveEnabled(true)

        assertFalse(store.current().passive.canRecordCheckIn())
        assertTrue(store.current().passive.isEmpty)

        store.setPassiveSignals(store.current().passive.copy(unlocks = true))
        assertTrue(store.current().passive.canRecordCheckIn())
    }

    @Test
    fun `location sharing needs both the setting and the consent`() = runTest {
        val store = SettingsStore(InMemorySettingsStorage())
        store.setLocationSharing(
            LocationSharing(rememberLastKnown = true, shareWithGuardiansOnMiss = true),
        )

        assertFalse(
            store.current().location.mayShareWithGuardian(store.current().consents),
            "the switch is on but the user never agreed to share their position",
        )

        store.grantConsent(GuardianCapability.Location)

        assertTrue(store.current().location.mayShareWithGuardian(store.current().consents))

        store.withdrawConsent(GuardianCapability.Location)

        assertFalse(
            store.current().location.mayShareWithGuardian(store.current().consents),
            "a withdrawn consent overrides a switch the user left on",
        )
    }

    @Test
    fun `a granted push permission with every category off still cannot interrupt`() = runTest {
        val store = SettingsStore(InMemorySettingsStorage())
        store.setPushPermissionState(PermissionState.Granted)

        assertTrue(store.current().notifications.mayInterrupt)

        store.setNotifications(
            NotificationPermissions(push = PermissionState.Granted, medicines = false, checkIn = false),
        )

        assertFalse(
            store.current().notifications.mayInterrupt,
            "saying 'notifications are on' here would be a lie the user finds out at the worst moment",
        )
    }

    @Test
    fun `a permanently denied permission is distinguishable from a merely denied one`() = runTest {
        val store = SettingsStore(InMemorySettingsStorage())

        store.setPushPermissionState(PermissionState.Denied)
        assertEquals(PermissionState.Denied, store.current().notifications.push)

        store.setPushPermissionState(PermissionState.PermanentlyDenied)
        assertEquals(
            PermissionState.PermanentlyDenied,
            store.current().notifications.push,
            "the recovery differs: this one needs a trip to system settings",
        )
        assertEquals(PermissionState.PermanentlyDenied, PermissionState.fromCode("permanently_denied"))
        assertEquals(PermissionState.NotAsked, PermissionState.fromCode(null))
    }

    @Test
    fun `concurrent writes to different settings do not lose one another`() = runTest {
        // Two screens, editing at once. With a read-decide-write that is not
        // serialised, the second write is computed from a value the first has
        // already replaced and one of the two is silently dropped.
        val store = SettingsStore(InMemorySettingsStorage())

        val language = async { store.setLanguage(AppLanguage.Marathi) }
        val consent = async { store.grantConsent(GuardianCapability.Alerts) }
        val window = async { store.setCheckInWindow(CheckInWindow(startMinuteOfDay = 7 * 60)) }
        language.await()
        consent.await()
        window.await()

        val settings = store.current()
        assertEquals(AppLanguage.Marathi, settings.language)
        assertEquals(7 * 60, settings.checkInWindow.startMinuteOfDay)
        assertTrue(settings.consents.isGranted(GuardianCapability.Alerts, 1))
    }

    @Test
    fun `the flow emits the new value on every change`() = runTest {
        val store = SettingsStore(InMemorySettingsStorage())
        val seen = mutableListOf<AppLanguage>()

        // UNDISPATCHED so the collector is subscribed before the first write,
        // rather than racing it on a scheduler this test does not control.
        val collector = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            store.settings.collect { seen += it.language }
        }

        store.setLanguage(AppLanguage.Hindi)
        store.setLanguage(AppLanguage.Tamil)
        collector.cancel()

        assertEquals(listOf(AppLanguage.default, AppLanguage.Hindi, AppLanguage.Tamil), seen)
    }

    @Test
    fun `one read of current does not disturb the flow`() = runTest {
        val store = SettingsStore(InMemorySettingsStorage())

        assertEquals(AppSettings(), store.current())
        assertEquals(AppSettings(), store.settings.first())
    }
}
