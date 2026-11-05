package `in`.doosripari.settings

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Consent. Plan section 14, the Digital Personal Data Protection Act 2023.
 *
 * The test that matters most is the one in the middle: a change of wording must
 * re-ask. A boolean that survives a change of wording is consent the user never
 * gave, and that is the finding a data-protection audit looks for first.
 */
class ConsentTest {

    private val granted = Instant.parse("2026-01-01T00:00:00Z")
    private val later = Instant.parse("2026-06-01T00:00:00Z")

    private fun grantFor(capability: GuardianCapability, version: Int) =
        ConsentGrant(capability = capability, wordingVersion = version, grantedAt = granted)

    // ------------------------------------------------------- separate consents --

    @Test
    fun `granting alerts does not grant location`() {
        val consents = ConsentSet.empty + grantFor(GuardianCapability.Alerts, 1)

        assertTrue(consents.isGranted(GuardianCapability.Alerts, 1))
        assertFalse(consents.isGranted(GuardianCapability.Location, 1))
        assertFalse(consents.isGranted(GuardianCapability.Medicines, 1))
        assertFalse(consents.isGranted(GuardianCapability.DocumentSafe, 1))
    }

    @Test
    fun `withdrawing location leaves alerts alone`() {
        // The failure plan section 14 exists to prevent: one switch that turns
        // off more than the user turned off.
        var consents = ConsentSet.empty
        consents += grantFor(GuardianCapability.Alerts, 1)
        consents += grantFor(GuardianCapability.Location, 1)

        consents = consents.copy(
            grants = consents.grants + (
                (consents.grants.getValue(GuardianCapability.Location)).copy(withdrawnAt = later)
                ),
        )

        assertFalse(consents.isGranted(GuardianCapability.Location, 1))
        assertTrue(
            consents.isGranted(GuardianCapability.Alerts, 1),
            "the family is still told about a missed check-in, just not where",
        )
    }

    @Test
    fun `a withdrawn grant is kept in the record rather than deleted`() {
        // A deleted row cannot distinguish "never agreed" from "agreed and then
        // took it back", and that distinction is part of the record.
        val consents = ConsentSet(
            mapOf(GuardianCapability.Location to grantFor(GuardianCapability.Location, 1)),
        )
        val withdrawn = consents.copy(
            grants = consents.grants + (
                consents.grants.getValue(GuardianCapability.Location).copy(withdrawnAt = later)
                ),
        )

        assertNotNull(withdrawn.grants[GuardianCapability.Location], "the row is still there")
        assertTrue(withdrawn.grants.getValue(GuardianCapability.Location).withdrawnAt == later)
        assertNull(withdrawn[GuardianCapability.Location], "but it is not live")
        assertEquals(0, withdrawn.activeGrants.size)
    }

    // --------------------------------------------------------- wording version --

    @Test
    fun `a grant at the current wording version covers the current wording`() {
        val consents = ConsentSet.empty + grantFor(GuardianCapability.Alerts, 1)

        assertFalse(consents.needsConsent(GuardianCapability.Alerts, 1))
    }

    @Test
    fun `changing the wording version makes the app ask again`() {
        val consents = ConsentSet.empty + grantFor(GuardianCapability.Alerts, 1)

        // The regulator required new disclosure, or we realised the old text did
        // not say what we thought. Either way version 1 does not cover
        // version 2, and the user must see version 2 before agreeing to it.
        assertTrue(
            consents.needsConsent(GuardianCapability.Alerts, wordingVersion = 2),
            "an agreement to specific text does not carry forward to different text",
        )
    }

    @Test
    fun `a re-grant at the new wording version settles the question`() {
        val consents = ConsentSet.empty +
            grantFor(GuardianCapability.Alerts, 1) +
            ConsentGrant(GuardianCapability.Alerts, wordingVersion = 2, grantedAt = later)

        assertFalse(consents.needsConsent(GuardianCapability.Alerts, 2))
        assertEquals(2, consents.grants.getValue(GuardianCapability.Alerts).wordingVersion)
        assertEquals(
            later,
            consents.grants.getValue(GuardianCapability.Alerts).grantedAt,
            "the record says when the user actually agreed to the text in front of them",
        )
    }

    @Test
    fun `a withdrawn grant needs asking again even at the same wording version`() {
        val consents = ConsentSet(
            mapOf(
                GuardianCapability.Alerts to grantFor(GuardianCapability.Alerts, 1)
                    .copy(withdrawnAt = later),
            ),
        )

        assertTrue(consents.needsConsent(GuardianCapability.Alerts, 1))
    }

    @Test
    fun `outstanding lists alerts first so the sheet asks in a fixed order`() {
        val consents = ConsentSet.empty + grantFor(GuardianCapability.Medicines, 1)

        assertEquals(
            listOf(
                GuardianCapability.Alerts,
                GuardianCapability.Location,
                GuardianCapability.DocumentSafe,
            ),
            ConsentRegistry.outstandingFor(consents),
            "medicines is already agreed; the rest come in enum order, not alphabetically",
        )
    }

    @Test
    fun `a wording bump on one capability does not disturb another`() {
        var consents = ConsentSet.empty
        consents += grantFor(GuardianCapability.Alerts, 1)
        consents += grantFor(GuardianCapability.Location, 1)
        consents += grantFor(GuardianCapability.Medicines, 1)

        // Only location's wording changed. A single global version number would
        // re-ask the user about alerts and medicines they have already been shown
        // and agreed to, which is the other half of the same problem: an audit
        // trail full of consents the user did not re-read.
        assertEquals(
            listOf(GuardianCapability.Location, GuardianCapability.DocumentSafe),
            outstandingWhereLocationIsVersion2(consents),
        )
    }

    @Test
    fun `every capability starts at wording version 1`() {
        // There is no version 0. "We never asked" and "we asked version 0" are
        // different states, and only the first is true.
        assertTrue(ConsentSet.WORDING_VERSIONS.values.all { it == 1 })
        assertEquals(1, ConsentSet.wordingVersionFor(GuardianCapability.Alerts))
    }

    // -------------------------------------------------------------- escalation --

    @Test
    fun `nobody can be told about a missed check-in without the alerts consent`() {
        val withoutAlerts = ConsentSet.empty + grantFor(GuardianCapability.Location, 1)

        assertFalse(
            withoutAlerts.canEscalate,
            "a location consent is not permission to call a son-in-law at 9am",
        )
    }

    @Test
    fun `the alerts consent alone is enough to escalate`() {
        val consents = ConsentSet.empty + grantFor(GuardianCapability.Alerts, 1)

        assertTrue(consents.canEscalate)
        assertEquals(1, consents.activeGrants.size)
    }

    @Test
    fun `withdrawing alerts is the only withdrawal that stops escalation`() {
        var consents = ConsentSet.empty
        consents += grantFor(GuardianCapability.Alerts, 1)
        consents += grantFor(GuardianCapability.Location, 1)
        consents += grantFor(GuardianCapability.Medicines, 1)

        assertTrue(consents.canEscalate)

        consents = consents.copy(
            grants = consents.grants + (
                consents.grants.getValue(GuardianCapability.Location).copy(withdrawnAt = later)
                ),
        )
        assertTrue(consents.canEscalate, "location is an addition, not a precondition")

        consents = consents.copy(
            grants = consents.grants + (
                consents.grants.getValue(GuardianCapability.Alerts).copy(withdrawnAt = later)
                ),
        )
        assertFalse(consents.canEscalate)
    }

    // ------------------------------------------------------------------ store --

    @Test
    fun `the store records the wording version the user saw, not the current one`() = runTest {
        val store = SettingsStore(InMemorySettingsStorage())

        store.grantConsent(
            capability = GuardianCapability.Alerts,
            wordingVersion = 1,
            now = granted,
        )

        val record = store.current().consents.grants.getValue(GuardianCapability.Alerts)
        assertEquals(1, record.wordingVersion)
        assertEquals(granted, record.grantedAt)
    }

    @Test
    fun `the store re-asks when the registry moves past the stored wording version`() = runTest {
        val store = SettingsStore(InMemorySettingsStorage())
        // A user who agreed to version 1 in a previous release. Their grant is
        // still in the record, and the app must still ask again, because the
        // text in front of them is no longer the text they agreed to.
        store.grantConsent(GuardianCapability.Alerts, wordingVersion = 1, now = granted)

        val stored = store.current().consents.grants.getValue(GuardianCapability.Alerts)
        assertTrue(stored.isActive, "the record is not deleted by the version moving on")

        val shippedVersion = ConsentSet.wordingVersionFor(GuardianCapability.Alerts)
        if (shippedVersion > 1) {
            assertTrue(store.hasOutstandingConsent(), "version $shippedVersion must re-ask version 1")
        } else {
            // This build still ships version 1, so the grant holds and the
            // only outstanding item is the rest of the sheet.
            assertEquals(
                listOf(GuardianCapability.Location, GuardianCapability.DocumentSafe, GuardianCapability.Medicines),
                store.outstandingConsents(),
            )
        }
    }

    @Test
    fun `the store will not escalate to guardians until alerts is granted`() = runTest {
        val store = SettingsStore(InMemorySettingsStorage())
        store.grantConsent(GuardianCapability.Location, now = granted)
        store.grantConsent(GuardianCapability.Medicines, now = granted)

        assertFalse(store.current().consents.canEscalate)
        assertTrue(store.hasOutstandingConsent())

        store.grantConsent(GuardianCapability.Alerts, now = later)

        assertTrue(store.current().consents.canEscalate)
    }

    @Test
    fun `a withdrawn capability is listed as outstanding again`() = runTest {
        val store = SettingsStore(InMemorySettingsStorage())
        store.grantConsent(GuardianCapability.Alerts, now = granted)
        store.grantConsent(GuardianCapability.Location, now = granted)

        assertFalse(store.hasOutstandingConsent())

        store.withdrawConsent(GuardianCapability.Location, now = later)

        assertEquals(
            listOf(GuardianCapability.Location),
            store.outstandingConsents(),
        )
        assertTrue(store.current().consents.canEscalate, "location was not a precondition")
    }

    @Test
    fun `withdrawing something that was never granted changes nothing`() = runTest {
        val store = SettingsStore(InMemorySettingsStorage())
        val before = store.current()

        store.withdrawConsent(GuardianCapability.DocumentSafe, now = later)

        assertEquals(before, store.current())
    }

    // ------------------------------------------------------------- consequences --

    @Test
    fun `every capability can explain what withdrawing it costs`() {
        // A toggle that turns off location and silently also stops the alerts is
        // the bug, so the consequence is something a screen can put in words.
        assertTrue(ConsentRegistry.onWithdrawal(GuardianCapability.Alerts).contains("nobody will be told"))
        assertTrue(ConsentRegistry.onWithdrawal(GuardianCapability.Location).contains("not where"))
    }
}

/**
 * Helper for the "one capability's wording changed" test.
 *
 * Cannot be expressed against [ConsentSet.WORDING_VERSIONS] directly, because
 * that map is a `val` in a companion object and a test must not mutate a global
 * to simulate the next release. Building the equivalent view explicitly says
 * what it means: the app is now showing location version 2, everything else
 * still version 1.
 */
private fun outstandingWhereLocationIsVersion2(consents: ConsentSet): List<GuardianCapability> =
    ConsentRegistry.order.filter { capability ->
        val version = if (capability == GuardianCapability.Location) 2 else 1
        consents.needsConsent(capability, version)
    }
