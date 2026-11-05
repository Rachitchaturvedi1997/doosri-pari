package `in`.doosripari

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import `in`.doosripari.data.CheckInRepository
import `in`.doosripari.data.HttpCheckInRepository
import `in`.doosripari.feature.checkin.CheckInScreen
import `in`.doosripari.feature.checkin.CheckInViewModel
import `in`.doosripari.ui.theme.DoosriPariTheme
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DoosriPariTheme {
                CheckInRoute()
            }
        }
    }
}

/**
 * The single screen the vertical slice builds.
 *
 * The repository and the local day are constructed here rather than inside the
 * ViewModel so the ViewModel stays constructible from a test with a fake
 * repository — that is the whole reason it is a plain class with an interface
 * beside it.
 */
@Composable
private fun CheckInRoute(
    repository: CheckInRepository? = null,
    viewModel: CheckInViewModel? = null,
) {
    val vm: CheckInViewModel = viewModel ?: viewModel(factory = remember {
        factoryFor(
            repository ?: HttpCheckInRepository.create(DevUserId),
            Clock.System.todayIn(TimeZone.currentSystemDefault()).toString(),
        )
    })

    val state by vm.state.collectAsState()

    CheckInScreen(
        state = state,
        onCheckIn = vm::checkIn,
        onRetry = vm::refresh,
        // SOS is out of scope for this slice. It is wired to a no-op rather
        // than left as a dead button, but it must not look finished: plan
        // section 6.3 has SOS open a hold-and-countdown screen, and a plain
        // no-op on a red button labelled SOS is worse than not having it.
        onSos = { },
        addressAs = DevUserAddressAs,
    )
}

/**
 * Stands in for the signed-in user.
 *
 * There is no authentication yet — the plan puts OTP in phase 1 — so these are
 * development values. See HttpCheckInRepository for why shipping this would be
 * a problem.
 */
private const val DevUserId = "1"
private const val DevUserAddressAs = "Sharma ji"

private fun factoryFor(
    repository: CheckInRepository,
    localDay: String,
) = object : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        CheckInViewModel(repository, localDay) as T
}
