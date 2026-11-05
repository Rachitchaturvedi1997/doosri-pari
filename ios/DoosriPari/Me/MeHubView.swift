// UNTESTED — never compiled. See ios/README.md.
//
// Plan section 5, the "Main" tab — the hub. The plan's own list: "Pension
// Sahayak, toolkit, document safe, health, Yoga and Spiritual Centre, Meri
// Kahani, learn, family, settings".
//
// This screen holds no logic of its own; it is navigation and nothing else,
// per plan 5 ("Tabs (navigation only)").
//
// Two rows — Pension Sahayak and the toolkit — belong to modules other agents
// own. They are drawn here because plan 5 lists them on this screen, and they
// are wired through injected closures so this package neither reaches into
// another module nor blocks the shell from compiling before those modules
// land. See the two properties at the top of `MeHubView`.

import SwiftUI

@MainActor
final class MeHubViewModel: ObservableObject {
    /// The hub has nothing to load; the plan's rows are fixed. This model
    /// exists so the screen keeps the house shape (an ObservableObject per
    /// screen) and so the greeting line has one place to live.
    @Published private(set) var addressAs: String?

    init(addressAs: String? = nil) {
        self.addressAs = addressAs
    }

    var greeting: String {
        guard let addressAs else {
            return MeText.t("me_hub_title", "Main")
        }
        return String(
            format: MeText.t("me_hub_greeting", "Main, %1$@"),
            addressAs
        )
    }
}

struct MeHubView: View {
    @StateObject private var model: MeHubViewModel
    @Environment(\.colorScheme) private var scheme

    let meService: MeService
    let swasthyaService: SwasthyaService
    let yogService: YogService
    let adhyatmService: AdhyatmService
    let documentStore: DocumentSafeStore

    /// Other agents' modules. Defaults are no-ops so the shell compiles
    /// against this screen before those modules exist; the shell passes real
    /// destinations when it wires the tab.
    var onOpenPensionSahayak: () -> Void
    var onOpenToolkit: () -> Void

    /// SOS is an action, not a tab (plan 5); every main screen carries it.
    var onSOS: () -> Void

    init(
        model: MeHubViewModel = MeHubViewModel(),
        meService: MeService = MeSampleService(),
        swasthyaService: SwasthyaService = SwasthyaSampleService(),
        yogService: YogService = YogSampleService(),
        adhyatmService: AdhyatmService = AdhyatmSampleService(),
        documentStore: DocumentSafeStore = DocumentSafeLocalStore(),
        onOpenPensionSahayak: @escaping () -> Void = {},
        onOpenToolkit: @escaping () -> Void = {},
        onSOS: @escaping () -> Void = {}
    ) {
        _model = StateObject(wrappedValue: model)
        self.meService = meService
        self.swasthyaService = swasthyaService
        self.yogService = yogService
        self.adhyatmService = adhyatmService
        self.documentStore = documentStore
        self.onOpenPensionSahayak = onOpenPensionSahayak
        self.onOpenToolkit = onOpenToolkit
        self.onSOS = onSOS
    }

    var body: some View {
        NavigationStack {
            DoosriScreen(
                model.greeting,
                subtitle: MeText.t(
                    "me_hub_subtitle",
                    "Everything of yours, in one place"
                ),
                showsBackButton: false,
                toolbar: [.sos(action: onSOS)]
            ) {
                VStack(alignment: .leading, spacing: DoosriSpace.md) {
                    // Plan 5's order, kept exactly: pension first, settings
                    // last, because that is the order the plan reads in and
                    // the order a pensioner's questions arrive in.
                    sahayakRow
                    toolkitRow
                    documentSafeRow
                    healthRow
                    kendraRow
                    storiesRow
                    learnRow
                    familyRow
                    settingsRow

                    notAGovernmentServiceLine
                }
            }
        }
    }

    // MARK: - Rows

    private var sahayakRow: some View {
        DoosriListRow(
            systemImage: "questionmark.circle.fill",
            title: MeText.t("me_row_sahayak", "Pension Sahayak"),
            value: MeText.t("me_row_sahayak_value", "Ask a pension question"),
            onTap: onOpenPensionSahayak
        )
    }

    private var toolkitRow: some View {
        DoosriListRow(
            systemImage: "number.square.fill",
            title: MeText.t("me_row_toolkit", "Pension toolkit"),
            value: MeText.t("me_row_toolkit_value", "Calculators and forms"),
            onTap: onOpenToolkit
        )
    }

    private var documentSafeRow: some View {
        NavigationLink {
            DocumentSafeView(
                model: DocumentSafeViewModel(store: documentStore)
            )
        } label: {
            DoosriListRow(
                systemImage: "lock.doc.fill",
                title: MeText.t("me_row_safe", "Document safe"),
                value: MeText.t("me_row_safe_value", "Encrypted on this phone")
            )
        }
    }

    private var healthRow: some View {
        NavigationLink {
            SwasthyaHubView(
                model: SwasthyaHubViewModel(service: swasthyaService)
            )
        } label: {
            DoosriListRow(
                systemImage: "heart.text.square.fill",
                title: MeText.t("me_row_health", "Swasthya · Health"),
                value: MeText.t("me_row_health_value", "Medicines and readings")
            )
        }
    }

    private var kendraRow: some View {
        NavigationLink {
            KendraHomeView(
                yogService: yogService,
                adhyatmService: adhyatmService
            )
        } label: {
            DoosriListRow(
                systemImage: "figure.mind.and.body",
                title: MeText.t("me_row_kendra", "Yoga and Spiritual Centre"),
                value: MeText.t("me_row_kendra_value", "Yog Kendra · Adhyatm Kendra")
            )
        }
    }

    private var storiesRow: some View {
        NavigationLink {
            MeriKahaniView(
                model: MeriKahaniViewModel(service: meService)
            )
        } label: {
            DoosriListRow(
                systemImage: "book.fill",
                title: MeText.t("me_row_stories", "Meri Kahani"),
                value: MeText.t("me_row_stories_value", "My story, in my voice")
            )
        }
    }

    private var learnRow: some View {
        NavigationLink {
            SeekhoView(
                model: SeekhoViewModel(service: meService)
            )
        } label: {
            DoosriListRow(
                systemImage: "graduationcap.fill",
                title: MeText.t("me_row_learn", "Seekho"),
                value: MeText.t("me_row_learn_value", "Short lessons")
            )
        }
    }

    private var familyRow: some View {
        NavigationLink {
            FamilyConnectView(
                model: FamilyConnectViewModel(service: meService)
            )
        } label: {
            DoosriListRow(
                systemImage: "person.2.fill",
                title: MeText.t("me_row_family", "Family"),
                value: MeText.t("me_row_family_value", "Call, share, stay close")
            )
        }
    }

    private var settingsRow: some View {
        NavigationLink {
            SettingsView(
                model: SettingsViewModel(service: meService)
            )
        } label: {
            DoosriListRow(
                systemImage: "gearshape.fill",
                title: MeText.t("me_row_settings", "Settings"),
                value: MeText.t("me_row_settings_value", "Privacy, language, notifications")
            )
        }
    }

    /// Plan 14: "Independent app, not affiliated with any government
    /// department" — on Settings, in onboarding, in the store listings and on
    /// every Sahayak answer. This is its home on the hub.
    private var notAGovernmentServiceLine: some View {
        Text(MeText.t(
            "me_not_govt",
            "Independent app, not affiliated with any government department."
        ))
        .font(.doosriCaption)
        .foregroundStyle(DoosriPalette.inkSecondary(scheme))
        .padding(.top, DoosriSpace.sm)
    }
}

// MARK: - Shell binding

/// The tab shell binds to this name. `DoosriPariApp` builds the Main tab as
/// `MeView()`; the defaults above keep that compiling while the other agents'
/// modules land, and the shell can hand in live services when they do.
struct MeView: View {
    var body: some View {
        MeHubView()
    }
}