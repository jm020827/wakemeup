import Foundation

public enum NightPhase: Sendable { case ready, preparing, waiting, reserved, ringing, attention }
public enum MomentKind: Sendable { case start, onset, received, reservation, alarm, end, error, planned }
public struct SleepMoment: Identifiable, Equatable, Sendable {
    public var id: String
    public var kind: MomentKind
    public var title: String
    public var at: Date
    public var detail: String
    public var planned: Bool
    public init(_ id: String, _ kind: MomentKind, _ title: String, _ at: Date, detail: String = "", planned: Bool = false) {
        self.id = id; self.kind = kind; self.title = title; self.at = at; self.detail = detail; self.planned = planned
    }
}
public enum SleepPresentation {
    public static func phase(session: NightSession?, observing: Bool, alarmAllowed: Bool) -> NightPhase {
        guard let session else { return .ready }
        if session.status == .ringing { return .ringing }
        if !session.isActive { return session.hasReservations ? .attention : .ready }
        if session.status == .failed { return .attention }
        if session.status == .scheduling { return .preparing }
        if session.targetReserved { return .reserved }
        if !alarmAllowed || session.failure != nil { return .attention }
        return observing ? .waiting : .preparing
    }
    public static func visibleSessions(_ snapshot: WakeSnapshot) -> [NightSession] {
        snapshot.sessions.filter { $0.isActive || $0.onsetAt != nil || $0.alarmObservedAt != nil || $0.failure != nil || ($0.status == .completed && $0.endedAt != nil) }
    }
    public static func duration(_ minutes: Int) -> String {
        "\(minutes / 60)시간" + (minutes % 60 == 0 ? "" : " \(minutes % 60)분")
    }
    public static func time(_ date: Date?, seconds: Bool = false, timeZone: TimeZone = .autoupdatingCurrent) -> String {
        guard let date else { return "—" }
        let format = DateFormatter(); format.locale = Locale(identifier: "ko_KR")
        format.timeZone = timeZone; format.dateFormat = seconds ? "HH:mm:ss" : "HH:mm"
        return format.string(from: date)
    }
    public static func day(_ date: Date) -> String {
        let format = DateFormatter(); format.locale = Locale(identifier: "ko_KR")
        format.dateFormat = "M월 d일 · E요일"; return format.string(from: date)
    }
    public static func receiptDelay(_ session: NightSession) -> String {
        guard let onset = session.onsetAt, let received = session.receivedAt else { return "—" }
        let total = Int(received.timeIntervalSince(onset))
        guard total >= 0 else { return "시각 확인 필요" }
        return [(total >= 3600 ? "\(total / 3600)시간" : nil), (total % 3600 >= 60 ? "\(total % 3600 / 60)분" : nil), "\(total % 60)초"].compactMap { $0 }.joined(separator: " ")
    }
    public static func end(_ session: NightSession, now: Date) -> Date {
        session.alarmObservedAt ?? session.endedAt ?? (session.isActive ? now : session.receivedAt ?? session.startedAt)
    }
    public static func moments(session: NightSession, events: [NightEvent], now: Date) -> [SleepMoment] {
        var moments = [SleepMoment("start", .start, "감시 시작", session.startedAt)]
        if let onset = session.onsetAt { moments.append(SleepMoment("onset", .onset, "입면", onset, detail: "워치가 기록한 잠든 시각")) }
        if let received = session.receivedAt { moments.append(SleepMoment("received", .received, "기록 수신", received, detail: "입면 후 \(receiptDelay(session))")) }
        if let reserved = events.first(where: { $0.sessionID == session.id && $0.kind == .reserved }) {
            moments.append(SleepMoment("reserved", .reservation, "알람 예약", reserved.at, detail: "기상 \(time(session.alarmAt))"))
        }
        if let observed = session.alarmObservedAt {
            moments.append(SleepMoment("alarm", .alarm, session.firedKind == .backup ? "예비 알람 울림 확인" : "기상 알람 울림 확인", observed, detail: "앱에서 확인한 시각"))
        }
        if let ended = session.endedAt { moments.append(SleepMoment("end", .end, session.status == .cancelled ? "감시 종료" : "알람 해제", ended)) }
        if let failure = session.failure, let event = events.last(where: { $0.sessionID == session.id && [.missed, .scheduleFailed, .cancellationFailed].contains($0.kind) }) {
            moments.append(SleepMoment("error", .error, "확인 필요", event.at, detail: failure))
        }
        if let alarm = session.alarmAt, session.firedKind != .target {
            moments.append(SleepMoment("planned-target", .planned, session.targetReserved ? "기상 예정" : "계산된 기상", alarm, detail: "입면 + \(duration(session.targetMinutes))", planned: true))
        } else if let backup = session.backupAt, session.backupReserved, session.alarmObservedAt == nil {
            moments.append(SleepMoment("planned-backup", .planned, "예비 알람 예정", backup, planned: true))
        }
        return moments.sorted { $0.at == $1.at ? $0.id < $1.id : $0.at < $1.at }
    }
    public static func faceFraction(_ date: Date, timeZone: TimeZone = .autoupdatingCurrent) -> Double {
        var calendar = Calendar(identifier: .gregorian); calendar.timeZone = timeZone
        let components = calendar.dateComponents([.hour, .minute, .second, .nanosecond], from: date)
        let hours = Double(components.hour ?? 0) * 3600
        let minutes = Double(components.minute ?? 0) * 60
        let seconds = Double(components.second ?? 0)
        let fraction = Double(components.nanosecond ?? 0) / 1_000_000_000
        return (hours + minutes + seconds + fraction) / 86_400
    }
    public static func csv(_ events: [NightEvent]) -> String {
        let format = ISO8601DateFormatter(); format.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        func quote(_ value: String) -> String { "\"" + value.replacingOccurrences(of: "\"", with: "\"\"") + "\"" }
        return "sessionId,atUTC,kind,detail\n" + events.map { event in
            [event.sessionID?.uuidString ?? "", format.string(from: event.at), event.kind.rawValue, event.detail].map(quote).joined(separator: ",")
        }.joined(separator: "\n")
    }
}
