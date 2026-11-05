# iOS — written, never compiled

**No Swift in this directory has been compiled or run.** It was written on
Windows, with no macOS and no Xcode, per the decision to "write Swift, flag
untested". Everything here is a proposal that has never been through a
compiler, let alone a device.

What has changed since this was first written is that the app is now a
*buildable* project rather than a folder of source files, and that the parts
most likely to be wrong by accident are covered by tests. That is not the same
as being correct. Expect compile errors on the first `xcodebuild`.

## What is here

```
DoosriPari.xcodeproj/          app target + DoosriPariTests, shared scheme
DoosriPari/
  DoosriPariApp.swift          @main, wires the dev service
  CheckIn/                     models, HTTP service, view model, Today screen
  Design/                      plan section 4.3 palette and metrics
  Resources/Assets.xcassets/   app icon (drawn) and accent colour
  en.lproj, hi.lproj           the two written languages
DoosriPariTests/               six files, the tests
```

Open `DoosriPari.xcodeproj` and press Run. The scheme is shared, so
`xcodebuild -scheme DoosriPari test` works without touching Xcode first.

## The two things worth knowing

**1. The tests that matter most have never run.** `EscalationContractTests`
reads `shared/contract/escalation-contract.json` — the same file
`python/tests/test_contract.py` pins against the server — and checks that the
Swift `CheckInStage` enum still spells the wire names the server sends. This
replaces the note that used to sit in `CheckInModels.swift` saying the Swift
copy had to be "kept in step by hand". It is now checked by a machine, on the
half of the build that can check it. It will still need running once.

**2. One real bug is already fixed, and its test is the proof.**
`CheckInSnapshot` and `CheckInPreferences` used camelCase property names with no
key mapping, while the server sends `checked_at`, `window_start`,
`window_mins`, `share_location` and `paused_until`. Every response failed to
decode and the app could only ever say "Could not send. Please try again."
Android was fine — it maps the keys with `@SerialName`. The Swift models now
spell the keys out, and `CheckInModelsTests` and `CheckInServiceTests` decode
the real payloads. A second fix: `load()` never fetched settings, so the
"check-ins are paused" note on the Today screen was unreachable.

## Before any of it is trusted

1. `xcodebuild` — expect compile errors, and fix them before reading anything
   else. The likeliest candidates are the `#filePath` fallback in
   `ContractLoader`, and `CheckInViewModelTests`, which leans on a `@MainActor`
   fake and a held continuation.
2. A device, not the simulator. Plan section 8.1 asks for native SwiftUI rather
   than Compose Multiplatform precisely because Apple's HIG differs in ways a
   simulator does not reveal.
3. VoiceOver through the whole flow. The 96pt check-in button is the one
   control a frightened person has to find, and the tick is the only proof they
   have that somebody was told.
4. Devanagari, Tamil and Bengali at every text size, per plan section 4.3.
5. Dynamic Type at its largest setting, on a 4.7-inch screen.

## What is deliberately thin

There is no Swift state machine. The escalation logic lives in
`shared/feature-checkin` (Kotlin, tested) and on the server (Python, tested),
and Swift reads a stage name off the wire. That is the correct shape: three
implementations of a machine that wakes families at 3am would be a hazard, not
a feature. The contract file is what keeps the third one honest.

There is also no authentication, no guardian list, no offline store, and the SOS
button is wired to nothing. Those are the same gaps as on Android, and the same
ones that stop this shipping.
