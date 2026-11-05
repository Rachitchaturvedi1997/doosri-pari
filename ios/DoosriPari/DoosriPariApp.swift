// UNTESTED — never compiled. See ios/README.md.

import SwiftUI

@main
struct DoosriPariApp: App {

    /// The local backend while developing. `127.0.0.1` from the simulator
    /// reaches the host machine; a device needs the machine's LAN address.
    private static let baseURL = URL(string: "http://127.0.0.1:8000")!

    /// Stands in for the signed-in user. There is no authentication yet — the
    /// plan puts OTP in phase 1. See HTTPCheckInService for why shipping this
    /// would be a problem.
    private static let devUserId = "1"
    private static let devAddressAs = "Sharma ji"

    var body: some Scene {
        WindowGroup {
            CheckInView(
                model: CheckInViewModel(
                    service: HTTPCheckInService(
                        baseURL: Self.baseURL,
                        userId: Self.devUserId
                    )
                ),
                addressAs: Self.devAddressAs
            )
        }
    }
}
