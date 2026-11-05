package `in`.doosripari.settings

import `in`.doosripari.core.model.TextScale
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The text-scale rule, which is the one thing in this module that has to be
 * exactly right: **the in-app scale multiplies the system scale, it never
 * replaces it.**
 *
 * Plan section 6.16 says "System settings respected: Dynamic Type and the
 * Android font scale up to the largest sizes", and plan section 4.3 puts a
 * 20 pt body *before* Dynamic Type applies. Those two together mean the app's
 * own control is a second stage on top of the OS's, not a substitute for it.
 */
class TextScaleTest {

    @Test
    fun `the system setting is untouched when the app setting is the default`() {
        // The overwhelmingly common case: a user who has never opened Settings.
        // Their phone's chosen size must reach the screen untouched.
        assertEquals(1.0f, TextScale.System.composeWith(1.0f))
        assertEquals(1.3f, TextScale.System.composeWith(1.3f), 0.0001f)
        assertEquals(2.0f, TextScale.System.composeWith(2.0f), 0.0001f)
    }

    @Test
    fun `the in-app scale multiplies the system scale rather than replacing it`() {
        // A phone already at 1.5, with the app's "large" chosen on top.
        assertEquals(1.15f * 1.5f, TextScale.Large.composeWith(1.5f), 0.0001f)
        assertNotEquals(
            1.15f,
            TextScale.Large.composeWith(1.5f),
            "a system size of 1.5 must survive the app's own scale",
        )
    }

    @Test
    fun `a user at the maximum system size gets the maximum, not the minimum`() {
        // This is the case that motivates the whole feature. Somebody who has
        // been enlarging their phone for a decade, then chooses "large" in this
        // app, must not end up with less text than somebody who changed nothing.
        val untouched = TextScale.System.composeWith(2.0f)
        val scaled = TextScale.Large.composeWith(2.0f)

        assertTrue(scaled > untouched, "choosing a bigger in-app size must never shrink the text")
    }

    @Test
    fun `composition is clamped so an extreme phone still fits a card`() {
        // Android does offer a 3.0 accessibility font size. 3.0 x 1.3 does not
        // fit plan section 4.3's geometry, and a layout that overflows is a
        // layout that hides the button somebody needed.
        assertEquals(MAX_COMPOSED_TEXT_SCALE, TextScale.Largest.composeWith(3.0f))
        assertEquals(MAX_COMPOSED_TEXT_SCALE, TextScale.Largest.composeWith(10.0f))
    }

    @Test
    fun `a deliberately small system size is respected`() {
        // Some users set their phone smaller on purpose. Flooring at 1.0 would
        // overrule them.
        assertEquals(0.75f * 1.0f, TextScale.System.composeWith(0.75f), 0.0001f)
    }

    @Test
    fun `a nonsense system scale from the platform falls back to 1`() {
        // Some OEM builds report 0 or a negative when the setting has been
        // overridden. Rendering at 0pt would make the app unusable.
        assertEquals(1.0f, TextScale.System.composeWith(0f))
        assertEquals(1.0f, TextScale.System.composeWith(-2f))
        assertEquals(1.0f, TextScale.System.composeWith(Float.NaN))
        assertEquals(1.0f * 1.3f, TextScale.Largest.composeWith(0f), 0.0001f)
    }

    @Test
    fun `body size is 20pt before either scale applies`() = runTest {
        // Plan section 4.3. Not 14, not 16.
        val settings = AppSettings()

        assertEquals(20f, settings.bodySizePt(1.0f), 0.0001f)
        assertEquals(20f * 1.3f, settings.bodySizePt(1.3f), 0.0001f)
    }

    @Test
    fun `the store composes rather than exposing the bare setting`() = runTest {
        val storage = InMemorySettingsStorage()
        val system = DefaultSystemTextScaleSource(1.5f)
        val store = SettingsStore(storage, system)

        store.setTextScale(TextScale.Large)

        assertEquals(1.5f * 1.15f, store.composedTextScale.first(), 0.0001f)
    }

    @Test
    fun `changing the system scale while the app is open is picked up`() = runTest {
        // A user changes their font size in Android Settings with this app
        // backgrounded. A cached copy would be wrong the moment they came back.
        val system = DefaultSystemTextScaleSource(1.0f)
        val store = SettingsStore(InMemorySettingsStorage(), system)

        assertEquals(1.0f, store.composedTextScale.first(), 0.0001f)

        system.update(1.8f)

        assertEquals(1.8f, store.composedTextScale.first(), 0.0001f)
    }

    @Test
    fun `the in-app setting is remembered across a new store over the same storage`() = runTest {
        val storage = InMemorySettingsStorage()
        SettingsStore(storage).setTextScale(TextScale.Largest)

        // A restart builds a new store object; the user's choice has to survive.
        assertEquals(
            TextScale.Largest,
            SettingsStore(storage, FixedSystemTextScaleSource(1.2f)).current().textScale,
        )
    }
}
