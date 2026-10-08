import Foundation

@MainActor public protocol WakeAlarmScheduling: AnyObject {
    func schedule(id: UUID, sessionID: UUID, kind: WakeAlarmKind, at: Date) async throws
    func cancel(id: UUID) throws
    func registeredIDs() throws -> Set<UUID>
}
@MainActor public protocol WakeWatchBridging: AnyObject { func sync(_ session: NightSession) throws }
private actor OperationGate {
    var locked = false
    var waiters: [CheckedContinuation<Void, Never>] = []
    func acquire() async {
        if !locked { locked = true; return }
        await withCheckedContinuation { waiters.append($0) }
    }
    func release() {
        if waiters.isEmpty { locked = false } else { waiters.removeFirst().resume() }
    }
}
@MainActor public final class NightCoordinator {
    private let repository: any WakeRepository
    private let scheduler: any WakeAlarmScheduling
    private let bridge: any WakeWatchBridging
    private let now: () -> Date
    private let gate = OperationGate()
    public init(repository: any WakeRepository, scheduler: any WakeAlarmScheduling, bridge: any WakeWatchBridging, now: @escaping () -> Date = Date.init) {
        self.repository = repository; self.scheduler = scheduler; self.bridge = bridge; self.now = now
    }
    private func serial<T: Sendable>(_ body: @MainActor () async throws -> T) async throws -> T {
        await gate.acquire()
        do { let result = try await body(); await gate.release(); return result }
        catch { await gate.release(); throw error }
    }
    @discardableResult public func start(minutes: Int, backupAt: Date?, watchID: String?) async throws -> NightSession {
        try await serial {
            _ = try WakeMath.alarmAt(onset: now(), minutes: minutes)
            guard repository.snapshot.latest?.isActive != true else { throw WakeError.alreadyMonitoring }
            guard !repository.snapshot.sessions.contains(where: { !$0.isActive && $0.hasReservations }) else { throw WakeError.pendingCancellation }
            guard backupAt == nil || backupAt! > now() else { throw WakeError.invalidBackup }
            var session = NightSession(startedAt: now(), targetMinutes: minutes, backupAt: backupAt, watchID: watchID)
            try repository.save(session)
            if let backupAt {
                do {
                    try await scheduler.schedule(id: session.backupAlarmID, sessionID: session.id, kind: .backup, at: backupAt)
                    session.backupReserved = true
                } catch {
                    session.status = .failed; session.failure = "예비 알람을 예약하지 못했어요."
                    try log(session, .scheduleFailed, error.localizedDescription)
                }
                try repository.save(session)
            }
            try log(session, .started); sync(session)
            return session
        }
    }
    public func receive(_ receipt: SleepReceipt) async throws {
        try await serial {
            guard var session = repository.snapshot.latest else { return }
            let reason: String?
            if receipt.sessionID != session.id { reason = "이전 세션" }
            else if !session.isActive || session.status == .ringing { reason = "종료된 세션" }
            else if receipt.origin == .watchHealthKit, let expected = session.watchID, receipt.watchID != expected { reason = "다른 워치" }
            else if session.onsetAt != nil { reason = "첫 입면 유지" }
            else if receipt.onsetAt < session.startedAt { reason = "감시 시작 전 입면" }
            else if receipt.onsetAt > now() || receipt.observedAt < receipt.onsetAt || receipt.observedAt > now().addingTimeInterval(60) { reason = "유효하지 않은 시각" }
            else { reason = nil }
            if let reason { try log(session, .ignored, reason); return }
            session.onsetAt = receipt.onsetAt; session.receivedAt = now()
            if receipt.origin == .watchHealthKit { session.watchReceivedAt = receipt.observedAt }
            session.alarmAt = try WakeMath.alarmAt(onset: receipt.onsetAt, minutes: session.targetMinutes)
            session.status = .scheduling
            try repository.save(session)
            try repository.record(sessionID: session.id, at: receipt.onsetAt, kind: .onset, detail: receipt.origin.rawValue)
            try log(session, .received, "입면 후 \(Int(now().timeIntervalSince(receipt.onsetAt)))초")
            if session.alarmAt! <= now() {
                session.status = .failed; session.failure = "기상 시각이 지났어요. 예비 알람을 유지해요."
                try repository.save(session); try log(session, .missed)
            } else { try await scheduleTarget(session) }
            if let latest = repository.snapshot.latest { sync(latest) }
        }
    }
    private func scheduleTarget(_ previous: NightSession) async throws {
        var session = previous
        do { try await scheduler.schedule(id: session.targetAlarmID, sessionID: session.id, kind: .target, at: session.alarmAt!) }
        catch {
            session.status = .failed; session.targetReserved = false; session.failure = "기상 알람 예약 실패 · 예비 알람 유지"
            try repository.save(session); try log(session, .scheduleFailed, error.localizedDescription); return
        }
        session.status = .scheduled; session.targetReserved = true; session.failure = nil
        try repository.save(session)
        // Only replace the backup after the system accepted the target and the result is persisted.
        do { try scheduler.cancel(id: session.backupAlarmID); session.backupReserved = false }
        catch { session.failure = "기상 알람 예약됨 · 예비 알람 해제 확인 필요"; try log(session, .cancellationFailed, error.localizedDescription) }
        try repository.save(session); try log(session, .reserved)
    }
    public func cancel() async throws {
        try await serial {
            guard var session = repository.snapshot.latest, session.isActive || session.hasReservations else { return }
            session.status = .cancelled; session.endedAt = now()
            try repository.save(session)
            try cancelReservations(&session)
            try repository.save(session); try log(session, .cancelled); sync(session)
        }
    }
    private func cancelReservations(_ session: inout NightSession) throws {
        var failures: [String] = []
        do { try scheduler.cancel(id: session.targetAlarmID); session.targetReserved = false }
        catch { failures.append(error.localizedDescription) }
        do { try scheduler.cancel(id: session.backupAlarmID); session.backupReserved = false }
        catch { failures.append(error.localizedDescription) }
        session.failure = failures.isEmpty ? nil : "알람 해제를 다시 확인해 주세요."
        if !failures.isEmpty { try log(session, .cancellationFailed, failures.joined(separator: "; ")) }
    }
    public func observeAlert(alarmID: UUID, observedAt: Date) async throws {
        try await serial {
            guard var session = repository.snapshot.sessions.first(where: { $0.targetAlarmID == alarmID || $0.backupAlarmID == alarmID }), session.isActive || session.hasReservations, session.alarmObservedAt == nil else { return }
            let kind: WakeAlarmKind = session.targetAlarmID == alarmID ? .target : .backup
            // An actual system alert wins over stale reservation flags, including failed cancellation.
            if kind == .target { session.targetReserved = true } else { session.backupReserved = true }
            session.status = .ringing; session.alarmObservedAt = observedAt; session.firedKind = kind
            try repository.save(session); try repository.record(sessionID: session.id, at: observedAt, kind: .alarmObserved, detail: kind.rawValue)
            // Stop the other reservation; keep the currently alerting alarm for the user to dismiss.
            let other = kind == .target ? session.backupAlarmID : session.targetAlarmID
            do {
                try scheduler.cancel(id: other)
                if kind == .target { session.backupReserved = false } else { session.targetReserved = false }
            } catch { try log(session, .cancellationFailed, error.localizedDescription) }
            try repository.save(session); sync(session)
        }
    }
    public func dismiss(alarmID: UUID) async throws {
        try await serial {
            guard var session = repository.snapshot.sessions.first(where: { $0.targetAlarmID == alarmID || $0.backupAlarmID == alarmID }), session.isActive || session.hasReservations else { return }
            session.status = .completed; session.endedAt = now()
            try repository.save(session); try cancelReservations(&session)
            try repository.save(session); try log(session, .dismissed); sync(session)
        }
    }
    public func restore() async throws {
        try await serial {
            for var ended in repository.snapshot.sessions where !ended.isActive && ended.hasReservations {
                try cancelReservations(&ended); try repository.save(ended)
            }
            guard var session = repository.snapshot.latest, session.isActive, session.status != .ringing else { return }
            let registered = try scheduler.registeredIDs()
            if let at = session.alarmAt, at > now() {
                if !registered.contains(session.targetAlarmID) || !session.targetReserved { try await scheduleTarget(session) }
                else if session.backupReserved {
                    do { try scheduler.cancel(id: session.backupAlarmID); session.backupReserved = false; session.failure = nil; try repository.save(session) }
                    catch { try log(session, .cancellationFailed, error.localizedDescription) }
                }
            } else if session.alarmAt != nil {
                session.status = .failed; session.failure = "기상 시각이 지났어요."
                do { try scheduler.cancel(id: session.targetAlarmID); session.targetReserved = false }
                catch {
                    session.targetReserved = registered.contains(session.targetAlarmID)
                    session.failure = "지난 기상 알람 해제 확인 필요"
                    try log(session, .cancellationFailed, error.localizedDescription)
                }
                try repository.save(session); try log(session, .missed)
            }
            session = repository.snapshot.latest ?? session
            if !session.targetReserved, let backup = session.backupAt {
                if backup > now() {
                    if !registered.contains(session.backupAlarmID) {
                        do { try await scheduler.schedule(id: session.backupAlarmID, sessionID: session.id, kind: .backup, at: backup); session.backupReserved = true }
                        catch { session.backupReserved = false; session.failure = "예비 알람 복구 확인 필요"; try log(session, .scheduleFailed, error.localizedDescription) }
                    } else { session.backupReserved = true }
                } else {
                    do { try scheduler.cancel(id: session.backupAlarmID); session.backupReserved = false }
                    catch {
                        session.backupReserved = registered.contains(session.backupAlarmID)
                        session.failure = "지난 예비 알람 해제 확인 필요"
                        try log(session, .cancellationFailed, error.localizedDescription)
                    }
                }
                try repository.save(session)
            }
            try log(session, .restored); sync(session)
        }
    }
    private func log(_ session: NightSession, _ kind: EventKind, _ detail: String = "") throws {
        try repository.record(sessionID: session.id, at: now(), kind: kind, detail: detail)
    }
    private func sync(_ session: NightSession) {
        do { try bridge.sync(session) }
        catch { try? log(session, .transportError, error.localizedDescription) }
    }
}
