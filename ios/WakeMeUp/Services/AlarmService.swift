import AlarmKit
import AppIntents
import SwiftUI
import WakeCore

struct WakeAlarmMetadata: AlarmMetadata {
    var sessionID: String
    var kind: String
}
@MainActor final class AlarmService: WakeAlarmScheduling {
    private let manager = AlarmManager.shared
    private var updates: Task<Void, Never>?
    var onAlert: ((UUID, Date) async -> Void)?
    var onAuthorization: ((Bool) -> Void)?
    var authorized: Bool { manager.authorizationState == .authorized }
    var denied: Bool { manager.authorizationState == .denied }
    func requestAccess() async throws -> Bool {
        let state = try await manager.requestAuthorization()
        onAuthorization?(state == .authorized)
        return state == .authorized
    }
    func schedule(id: UUID, sessionID: UUID, kind: WakeAlarmKind, at: Date) async throws {
        guard authorized else { throw WakeError.alarmAccess }
        guard at > Date() else { throw WakeError.invalidBackup }
        // Stable session-specific IDs make recovery idempotent.
        if try manager.alarms.contains(where: { $0.id == id }) { return }
        let title: LocalizedStringResource = kind == .target ? "일어날 시간" : "예비 알람"
        let presentation = AlarmPresentation(alert: AlarmPresentation.Alert(title: title,
            stopButton: AlarmButton(text: "알람 끄기", textColor: .white, systemImageName: "stop.fill")))
        let attributes = AlarmAttributes(presentation: presentation,
            metadata: WakeAlarmMetadata(sessionID: sessionID.uuidString, kind: kind.rawValue), tintColor: Color(red: 0.55, green: 0.46, blue: 0.93))
        let configuration = AlarmManager.AlarmConfiguration<WakeAlarmMetadata>.alarm(schedule: .fixed(at), attributes: attributes,
            stopIntent: StopWakeAlarmIntent(alarmID: id.uuidString))
        _ = try await manager.schedule(id: id, configuration: configuration)
    }
    func cancel(id: UUID) throws {
        guard try manager.alarms.contains(where: { $0.id == id }) else { return }
        try stop(id: id)
        guard try manager.alarms.contains(where: { $0.id == id }) else { return }
        try manager.cancel(id: id)
    }
    func stop(id: UUID) throws {
        if try manager.alarms.contains(where: { $0.id == id && $0.state == .alerting }) { try manager.stop(id: id) }
    }
    func registeredIDs() throws -> Set<UUID> { Set(try manager.alarms.map(\.id)) }
    func beginObserving() {
        guard updates == nil else { return }
        updates = Task { [weak self] in
            guard let self else { return }
            for await alarms in self.manager.alarmUpdates {
                guard !Task.isCancelled else { return }
                self.onAuthorization?(self.authorized)
                for alarm in alarms where alarm.state == .alerting { await self.onAlert?(alarm.id, Date()) }
            }
        }
    }
    func checkCurrentAlerts() async {
        guard let alarms = try? manager.alarms else { return }
        for alarm in alarms where alarm.state == .alerting { await onAlert?(alarm.id, Date()) }
    }
}
struct StopWakeAlarmIntent: LiveActivityIntent {
    static var title: LocalizedStringResource = "wakemeup 알람 끄기"
    static var openAppWhenRun = false
    @Parameter(title: "알람 ID") var alarmID: String
    init() { self.alarmID = "" }
    init(alarmID: String) { self.alarmID = alarmID }
    @MainActor func perform() async throws -> some IntentResult {
        guard let id = UUID(uuidString: alarmID) else { return .result() }
        // Silence the system alert even if saving the app's history subsequently fails.
        try AppRuntime.shared.alarm.stop(id: id)
        try await AppRuntime.shared.coordinator.dismiss(alarmID: id)
        return .result()
    }
}
