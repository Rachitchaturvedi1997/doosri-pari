package `in`.doosripari.feature.checkin

import `in`.doosripari.data.CheckInSnapshot
import `in`.doosripari.feature.checkin.CheckInStage.CheckedIn
import `in`.doosripari.feature.checkin.CheckInStage.GuardiansAlerted
import `in`.doosripari.feature.checkin.CheckInStage.Resolved
import `in`.doosripari.feature.checkin.CheckInStage.Waiting
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class CheckInViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        // ViewModel.viewModelScope is hard-wired to Dispatchers.Main, which does
        // not exist in a JVM test. Swapping it for a test dispatcher is what
        // makes the coroutines here runnable at all.
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(
        repo: FakeCheckInRepository = FakeCheckInRepository(),
    ) = CheckInViewModel(repo, localDay = "2026-10-14")

    // ------------------------------------------------------------------ load --

    @Test
    fun `starts loading and then settles`() = runTest(dispatcher) {
        val vm = viewModel()
        assertTrue("should be loading before the network answers", vm.state.value.loading)

        advanceUntilIdle()

        assertFalse(vm.state.value.loading)
        assertEquals(Waiting, vm.state.value.today?.stage)
        assertNull(vm.state.value.error)
    }

    @Test
    fun `a failed load keeps the error and stops loading`() = runTest(dispatcher) {
        val repo = FakeCheckInRepository()
        repo.failTodayWith = IOException("no route to host")
        val vm = viewModel(repo)

        advanceUntilIdle()

        assertFalse("must not spin forever after a failure", vm.state.value.loading)
        assertEquals(CheckInError.Offline, vm.state.value.error)
    }

    // -------------------------------------------------------------- check in --

    @Test
    fun `pressing the button marks the day done`() = runTest(dispatcher) {
        val repo = FakeCheckInRepository()
        val vm = viewModel(repo)
        advanceUntilIdle()

        vm.checkIn()
        advanceUntilIdle()

        assertTrue(vm.state.value.isDone)
        assertEquals(1, repo.checkInCalls)
    }

    @Test
    fun `the button flips to done before the network answers`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        vm.checkIn()
        // No advanceUntilIdle: this is the state a user sees the instant after
        // pressing, while the request is still in flight.
        assertTrue(
            "a 96pt button must acknowledge the tap immediately",
            vm.state.value.isDone,
        )
        assertTrue(vm.state.value.sending)
    }

    @Test
    fun `a failed check-in rolls the button back`() = runTest(dispatcher) {
        val repo = FakeCheckInRepository()
        repo.failCheckInWith = IOException("offline")
        val vm = viewModel(repo)
        advanceUntilIdle()

        vm.checkIn()
        advanceUntilIdle()

        assertFalse("must not claim the user is fine when the send failed", vm.state.value.isDone)
        assertEquals(Waiting, vm.state.value.today?.stage)
        assertEquals(CheckInError.Offline, vm.state.value.error)
        assertFalse(vm.state.value.sending)
    }

    @Test
    fun `a second press while sending is ignored`() = runTest(dispatcher) {
        val repo = FakeCheckInRepository()
        val vm = viewModel(repo)
        advanceUntilIdle()

        vm.checkIn()
        vm.checkIn()
        advanceUntilIdle()

        assertEquals("a double tap must not send twice", 1, repo.checkInCalls)
    }

    @Test
    fun `a day already checked in cannot be checked in again`() = runTest(dispatcher) {
        val repo = FakeCheckInRepository(
            todayRow = CheckInSnapshot(
                day = "2026-10-14",
                stage = CheckedIn,
                enteredStageAt = null,
            )
        )
        val vm = viewModel(repo)
        advanceUntilIdle()

        vm.checkIn()
        advanceUntilIdle()

        assertEquals(0, repo.checkInCalls)
    }

    /**
     * A day closed by somebody reaching the user in person is Resolved, not
     * CheckedIn. The user should still be able to say they are fine
     * themselves — the plan does not treat a neighbour's visit as the user's
     * own check-in.
     */
    @Test
    fun `a day a helper resolved can still be checked in`() = runTest(dispatcher) {
        val repo = FakeCheckInRepository(
            todayRow = CheckInSnapshot(
                day = "2026-10-14",
                stage = Resolved,
                enteredStageAt = null,
            )
        )
        val vm = viewModel(repo)
        advanceUntilIdle()

        vm.checkIn()
        advanceUntilIdle()

        assertEquals(1, repo.checkInCalls)
    }

    // ------------------------------------------------------------ escalation --

    @Test
    fun `a day already alerted still lets the user check in`() = runTest(dispatcher) {
        val repo = FakeCheckInRepository(
            todayRow = CheckInSnapshot(
                day = "2026-10-14",
                stage = GuardiansAlerted,
                enteredStageAt = null,
            )
        )
        val vm = viewModel(repo)
        advanceUntilIdle()

        assertTrue("the screen must show that somebody has been told", vm.state.value.isEscalated)

        vm.checkIn()
        advanceUntilIdle()

        assertEquals(1, repo.checkInCalls)
        assertTrue(vm.state.value.isDone)
    }
}
