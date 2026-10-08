import Foundation

public enum NightStatus: String, Codable, Sendable {
    case monitoring, scheduling, scheduled, failed, ringing, completed, cancelled
}
public enum WakeAlarmKind: String, Codable, Sendable { case target, backup }
public enum ReceiptOrigin: String, Codable, Sendable { case phoneHealthKit, watchHealthKit }
public enum EventKind: String, Codable, Sendable {
    case started, onset, received, reserved, alarmObserved, dismissed, cancelled, missed
    case scheduleFailed, cancellationFailed, restored, ignored, healthError, transportError
}
public struct WakeSettings: Codable, Equatable, Sendable {
    public var targetMinutes: Int
    public var backupMinutes: Int?
    public var healthRequested: Bool
    public var onboardingComplete: Bool
    public init(targetMinutes: Int = 450, backupMinutes: Int? = 420, healthRequested: Bool = false, onboardingComplete: Bool = false) {
        self.targetMinutes = targetMinutes; self.backupMinutes = backupMinutes
        self.healthRequested = healthRequested; self.onboardingComplete = onboardingComplete
    }
}
public struct NightSession: Identifiable, Codable, Equatable, Sendable {
    public var id: UUID
    public var startedAt: Date
    public var targetMinutes: Int
    public var backupAt: Date?
    public var watchID: String?
    public var targetAlarmID: UUID
    public var backupAlarmID: UUID
    public var onsetAt: Date?
    public var receivedAt: Date?
    public var watchReceivedAt: Date?
    public var alarmAt: Date?
    public var status: NightStatus
    public var targetReserved: Bool
    public var backupReserved: Bool
    public var alarmObservedAt: Date?
    public var firedKind: WakeAlarmKind?
    public var endedAt: Date?
    public var failure: String?
    public var isActive: Bool { status != .completed && status != .cancelled }
    public var hasReservations: Bool { targetReserved || backupReserved }
    public init(id: UUID = UUID(), startedAt: Date, targetMinutes: Int, backupAt: Date? = nil, watchID: String? = nil) {
        self.id = id; self.startedAt = startedAt; self.targetMinutes = targetMinutes
        self.backupAt = backupAt; self.watchID = watchID
        targetAlarmID = UUID(); backupAlarmID = UUID(); status = .monitoring
        targetReserved = false; backupReserved = false
    }
    public func alarmID(_ kind: WakeAlarmKind) -> UUID { kind == .target ? targetAlarmID : backupAlarmID }
}
public struct NightEvent: Identifiable, Codable, Equatable, Sendable {
    public var id: UUID
    public var sessionID: UUID?
    public var at: Date
    public var kind: EventKind
    public var detail: String
    public init(id: UUID = UUID(), sessionID: UUID?, at: Date, kind: EventKind, detail: String = "") {
        self.id = id; self.sessionID = sessionID; self.at = at; self.kind = kind; self.detail = detail
    }
}
public struct SleepReceipt: Codable, Equatable, Sendable {
    public var sampleID: UUID
    public var sessionID: UUID
    public var onsetAt: Date
    public var observedAt: Date
    public var origin: ReceiptOrigin
    public var watchID: String?
    public init(sampleID: UUID, sessionID: UUID, onsetAt: Date, observedAt: Date, origin: ReceiptOrigin, watchID: String? = nil) {
        self.sampleID = sampleID; self.sessionID = sessionID; self.onsetAt = onsetAt
        self.observedAt = observedAt; self.origin = origin; self.watchID = watchID
    }
}
public struct WakeSnapshot: Codable, Equatable, Sendable {
    public var settings: WakeSettings
    public var sessions: [NightSession]
    public var events: [NightEvent]
    public var latest: NightSession? { sessions.first }
    public init(settings: WakeSettings = WakeSettings(), sessions: [NightSession] = [], events: [NightEvent] = []) {
        self.settings = settings; self.sessions = sessions; self.events = events
    }
}
public struct WatchCommand: Codable, Equatable, Sendable {
    public var sessionID: UUID
    public var startedAt: Date
    public var targetMinutes: Int
    public var active: Bool
    public var onsetAt: Date?
    public var alarmAt: Date?
    public init(_ session: NightSession) {
        sessionID = session.id; startedAt = session.startedAt; targetMinutes = session.targetMinutes
        active = session.isActive && session.status != .ringing
        onsetAt = session.onsetAt; alarmAt = session.alarmAt
    }
}
public struct WatchReport: Codable, Equatable, Sendable {
    public var watchID: String
    public var name: String
    public var healthAvailable: Bool
    public var healthRequested: Bool
    public var observing: Bool
    public var sessionID: UUID?
    public var at: Date
    public init(watchID: String, name: String, healthAvailable: Bool, healthRequested: Bool, observing: Bool, sessionID: UUID?, at: Date) {
        self.watchID = watchID; self.name = name; self.healthAvailable = healthAvailable
        self.healthRequested = healthRequested; self.observing = observing; self.sessionID = sessionID; self.at = at
    }
}
public struct SyncEnvelope: Codable, Sendable {
    public var version = 1
    public var revision: Int64
    public var command: WatchCommand?
    public var report: WatchReport?
    public var receipt: SleepReceipt?
    public var acknowledgedSampleID: UUID?
    public init(revision: Int64 = 0, command: WatchCommand? = nil, report: WatchReport? = nil, receipt: SleepReceipt? = nil, acknowledgedSampleID: UUID? = nil) {
        self.revision = revision; self.command = command; self.report = report
        self.receipt = receipt; self.acknowledgedSampleID = acknowledgedSampleID
    }
}
public enum WakeMath {
    public static func alarmAt(onset: Date, minutes: Int) throws -> Date {
        guard minutes >= 360 && minutes <= 10_080 else { throw WakeError.invalidDuration }
        return onset.addingTimeInterval(Double(minutes) * 60)
    }
    public static func nextBackup(now: Date, minutes: Int, timeZone: TimeZone = .autoupdatingCurrent) throws -> Date {
        guard (0..<1440).contains(minutes) else { throw WakeError.invalidBackup }
        var calendar = Calendar(identifier: .gregorian); calendar.timeZone = timeZone
        guard let date = calendar.nextDate(after: now, matching: DateComponents(hour: minutes / 60, minute: minutes % 60, second: 0), matchingPolicy: .nextTime, repeatedTimePolicy: .first) else { throw WakeError.invalidBackup }
        return date
    }
}
public enum WakeError: Error, LocalizedError, Sendable {
    case invalidDuration, invalidBackup, alreadyMonitoring, pendingCancellation, alarmAccess, storage(String)
    public var errorDescription: String? {
        switch self {
        case .invalidDuration: "목표 시간은 6시간 이상으로 설정해 주세요."
        case .invalidBackup: "예비 알람 시각을 확인해 주세요."
        case .alreadyMonitoring: "진행 중인 감시를 먼저 끝내 주세요."
        case .pendingCancellation: "이전 알람의 해제를 먼저 확인해 주세요."
        case .alarmAccess: "알람을 허용해 주세요."
        case .storage(let detail): "기록을 저장하지 못했어요. \(detail)"
        }
    }
}
