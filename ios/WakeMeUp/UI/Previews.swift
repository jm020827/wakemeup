#if DEBUG
import SwiftUI
import WakeCore

// Preview-only examples. The running app never creates or imports these records.
@MainActor private final class PreviewRepository: WakeRepository {
    var snapshot: WakeSnapshot
    init(snapshot: WakeSnapshot) { self.snapshot = snapshot }
    func commit(_ snapshot: WakeSnapshot) throws { self.snapshot = snapshot }
}
@MainActor private enum PreviewModels {
    static func make(reserved: Bool = false) -> AppModel {
        var snapshot = WakeSnapshot()
        snapshot.settings.healthRequested = true; snapshot.settings.onboardingComplete = true
        if reserved {
            var session = NightSession(startedAt: Date().addingTimeInterval(-5400), targetMinutes: 450,
                                       backupAt: Date().addingTimeInterval(8 * 3600), watchID: "preview")
            session.onsetAt = Date().addingTimeInterval(-3600)
            session.receivedAt = session.onsetAt!.addingTimeInterval(1200)
            session.alarmAt = session.onsetAt!.addingTimeInterval(450 * 60)
            session.targetReserved = true; session.status = .scheduled
            snapshot.sessions = [session]
            snapshot.events = [NightEvent(sessionID: session.id, at: session.receivedAt!, kind: .reserved)]
        }
        let repository = PreviewRepository(snapshot: snapshot)
        let alarm = AlarmService(); let health = HealthSleepReader()
        let bridge = PhoneWatchBridge(transport: WatchTransport())
        let coordinator = NightCoordinator(repository: repository, scheduler: alarm, bridge: bridge)
        let model = AppModel(repository: repository, alarm: alarm, health: health, coordinator: coordinator,
                             archiveURL: URL(fileURLWithPath: "/preview-only"), startupError: nil)
        model.alarmAllowed = true; model.observing = true; model.permissionsChecked = true
        return model
    }
}
#Preview("오늘 밤 · 밝게") {
    NavigationStack { TonightView(model: PreviewModels.make(), openFlow: {}) }.tint(Palette.accent)
}
#Preview("알람 예약 · 어둡게") {
    NavigationStack { TonightView(model: PreviewModels.make(reserved: true), openFlow: {}) }.tint(Palette.accent).preferredColorScheme(.dark)
}
#Preview("수면 흐름") {
    NavigationStack { SleepFlowView(model: PreviewModels.make(reserved: true)) }.tint(Palette.accent)
}
#endif
