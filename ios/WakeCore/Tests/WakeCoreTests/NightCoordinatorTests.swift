import Foundation
import XCTest
@testable import WakeCore

@MainActor private final class MemoryRepository: WakeRepository {
    var snapshot = WakeSnapshot()
    func commit(_ snapshot: WakeSnapshot) throws { self.snapshot = snapshot }
}
@MainActor private final class TestScheduler: WakeAlarmScheduling {
    var alarms: [UUID: Date] = [:]
    var targetFailure = false
    var cancellationFailure = false
    var beforeSchedule: (() -> Void)?
    func schedule(id: UUID, sessionID: UUID, kind: WakeAlarmKind, at: Date) async throws {
        beforeSchedule?(); await Task.yield()
        if kind == .target && targetFailure { throw WakeError.alarmAccess }
        alarms[id] = at
    }
    func cancel(id: UUID) throws {
        if cancellationFailure && alarms[id] != nil { throw WakeError.alarmAccess }
        alarms[id] = nil
    }
    func registeredIDs() throws -> Set<UUID> { Set(alarms.keys) }
}
@MainActor private final class TestBridge: WakeWatchBridging {
    var failure = false
    var sessions: [NightSession] = []
    func sync(_ session: NightSession) throws {
        if failure { throw WakeError.storage("offline") }
        sessions.append(session)
    }
}
@MainActor private final class TestClock { var date = Date(timeIntervalSince1970: 1_740_000_000) }

@MainActor final class NightCoordinatorTests: XCTestCase {
    private var repository: MemoryRepository!
    private var scheduler: TestScheduler!
    private var bridge: TestBridge!
    private var clock: TestClock!
    private var coordinator: NightCoordinator!
    override func setUp() async throws {
        repository = MemoryRepository(); scheduler = TestScheduler(); bridge = TestBridge(); clock = TestClock()
        let clock = clock!
        coordinator = NightCoordinator(repository: repository, scheduler: scheduler, bridge: bridge, now: { clock.date })
    }
    private func start(backup: Bool = true) async throws -> NightSession {
        try await coordinator.start(minutes: 360, backupAt: backup ? clock.date.addingTimeInterval(9 * 3600) : nil, watchID: "watch-a")
    }
    private func receipt(_ session: NightSession, offset: Double = 1200, origin: ReceiptOrigin = .watchHealthKit) -> SleepReceipt {
        SleepReceipt(sampleID: UUID(), sessionID: session.id, onsetAt: session.startedAt.addingTimeInterval(offset), observedAt: clock.date, origin: origin, watchID: "watch-a")
    }
    func testStartingReservesBackupAndDoesNotInventOnset() async throws {
        let session = try await start()
        XCTAssertNil(session.onsetAt); XCTAssertNil(session.alarmAt)
        XCTAssertTrue(session.backupReserved); XCTAssertEqual(scheduler.alarms[session.backupAlarmID], session.backupAt)
        XCTAssertEqual(bridge.sessions.last?.id, session.id)
    }
    func testNoWatchIsRequiredForPhoneHealthKitAndBackup() async throws {
        let session = try await coordinator.start(minutes: 450, backupAt: clock.date.addingTimeInterval(3600), watchID: nil)
        clock.date = session.startedAt.addingTimeInterval(1800)
        try await coordinator.receive(receipt(session, offset: 600, origin: .phoneHealthKit))
        XCTAssertTrue(repository.snapshot.latest!.targetReserved)
    }
    func testThreeHourDelayUsesOriginalOnsetAndReplacesBackup() async throws {
        let session = try await start()
        clock.date = session.startedAt.addingTimeInterval(1200 + 3 * 3600)
        let event = receipt(session)
        try await coordinator.receive(event)
        let saved = repository.snapshot.latest!
        XCTAssertEqual(saved.onsetAt, event.onsetAt)
        XCTAssertEqual(saved.receivedAt, clock.date)
        XCTAssertEqual(saved.alarmAt, event.onsetAt.addingTimeInterval(6 * 3600))
        XCTAssertEqual(scheduler.alarms.count, 1); XCTAssertNil(scheduler.alarms[session.backupAlarmID])
    }
    func testDurableSchedulingIntentExistsBeforeSystemReservation() async throws {
        let session = try await start(backup: false)
        clock.date = session.startedAt.addingTimeInterval(1800)
        let repository = repository!
        scheduler.beforeSchedule = { XCTAssertEqual(repository.snapshot.latest?.status, .scheduling) }
        try await coordinator.receive(receipt(session))
    }
    func testFirstValidOnsetSurvivesDuplicatesAndLaterSleep() async throws {
        let session = try await start()
        clock.date = session.startedAt.addingTimeInterval(3600)
        try await coordinator.receive(receipt(session))
        let first = repository.snapshot.latest!.onsetAt
        try await coordinator.receive(receipt(session, offset: 2400))
        XCTAssertEqual(repository.snapshot.latest!.onsetAt, first)
        XCTAssertEqual(repository.snapshot.events.filter { $0.kind == .onset }.count, 1)
    }
    func testOldSessionAndAnotherWatchAreIgnored() async throws {
        let session = try await start()
        clock.date = session.startedAt.addingTimeInterval(3600)
        var old = receipt(session); old.sessionID = UUID()
        var other = receipt(session); other.watchID = "watch-b"
        try await coordinator.receive(old); try await coordinator.receive(other)
        XCTAssertNil(repository.snapshot.latest!.onsetAt)
    }
    func testBeforeStartAndFutureTimestampsAreIgnored() async throws {
        let session = try await start()
        clock.date = session.startedAt.addingTimeInterval(3600)
        try await coordinator.receive(receipt(session, offset: -1))
        var future = receipt(session, offset: 5000); future.observedAt = future.onsetAt
        try await coordinator.receive(future)
        var invalid = receipt(session); invalid.observedAt = invalid.onsetAt.addingTimeInterval(-1)
        try await coordinator.receive(invalid)
        XCTAssertNil(repository.snapshot.latest!.onsetAt)
    }
    func testPastWakeNeverSchedulesImmediateAlarmAndKeepsBackup() async throws {
        let session = try await start()
        clock.date = session.startedAt.addingTimeInterval(8 * 3600)
        try await coordinator.receive(receipt(session, offset: 600))
        XCTAssertEqual(repository.snapshot.latest!.status, .failed)
        XCTAssertTrue(repository.snapshot.latest!.backupReserved)
        XCTAssertNil(scheduler.alarms[session.targetAlarmID])
    }
    func testTargetFailurePreservesOnsetAndOriginalBackup() async throws {
        let session = try await start()
        clock.date = session.startedAt.addingTimeInterval(3600); scheduler.targetFailure = true
        try await coordinator.receive(receipt(session))
        XCTAssertNotNil(repository.snapshot.latest!.onsetAt)
        XCTAssertTrue(repository.snapshot.latest!.backupReserved)
        XCTAssertEqual(scheduler.alarms.count, 1)
    }
    func testCancellationPersistsAndRejectsLateReceipts() async throws {
        let session = try await start(); clock.date = session.startedAt.addingTimeInterval(3600)
        try await coordinator.cancel(); try await coordinator.receive(receipt(session))
        XCTAssertEqual(repository.snapshot.latest!.status, .cancelled)
        XCTAssertNil(repository.snapshot.latest!.onsetAt); XCTAssertTrue(scheduler.alarms.isEmpty)
    }
    func testFailedCancellationCannotBeHiddenByNewNightAndIsRetried() async throws {
        _ = try await start(); scheduler.cancellationFailure = true
        try await coordinator.cancel()
        XCTAssertTrue(repository.snapshot.latest!.hasReservations)
        do { _ = try await start(); XCTFail("Pending system alarm must block a new night") } catch { }
        scheduler.cancellationFailure = false; try await coordinator.restore()
        XCTAssertFalse(repository.snapshot.latest!.hasReservations)
        _ = try await start()
    }
    func testRestoreReservesOriginalTimeWithoutMovingOnset() async throws {
        let session = try await start(); clock.date = session.startedAt.addingTimeInterval(3600)
        try await coordinator.receive(receipt(session))
        let saved = repository.snapshot.latest!
        scheduler.alarms.removeAll(); clock.date = clock.date.addingTimeInterval(600)
        try await coordinator.restore()
        XCTAssertEqual(repository.snapshot.latest!.onsetAt, saved.onsetAt)
        XCTAssertEqual(scheduler.alarms[saved.targetAlarmID], saved.alarmAt)
    }
    func testAlarmRemovalAfterDueDoesNotFabricateAnActualRing() async throws {
        let session = try await start(backup: false); clock.date = session.startedAt.addingTimeInterval(3600)
        try await coordinator.receive(receipt(session))
        clock.date = repository.snapshot.latest!.alarmAt!.addingTimeInterval(60); scheduler.alarms.removeAll()
        try await coordinator.restore()
        XCTAssertNil(repository.snapshot.latest!.alarmObservedAt)
        XCTAssertFalse(repository.snapshot.events.contains { $0.kind == .alarmObserved })
    }
    func testObservedAlertAndStopAreActualSeparateEvents() async throws {
        let session = try await start(backup: false); clock.date = session.startedAt.addingTimeInterval(3600)
        try await coordinator.receive(receipt(session)); clock.date = repository.snapshot.latest!.alarmAt!
        try await coordinator.observeAlert(alarmID: session.targetAlarmID, observedAt: clock.date)
        XCTAssertEqual(repository.snapshot.latest!.status, .ringing)
        clock.date = clock.date.addingTimeInterval(10); try await coordinator.dismiss(alarmID: session.targetAlarmID)
        XCTAssertEqual(repository.snapshot.latest!.status, .completed)
        XCTAssertEqual(repository.snapshot.latest!.endedAt, clock.date)
        XCTAssertTrue(scheduler.alarms.isEmpty)
    }
    func testStopWithoutObservedAlertDoesNotInventRingTimestamp() async throws {
        let session = try await start(); try await coordinator.dismiss(alarmID: session.backupAlarmID)
        XCTAssertEqual(repository.snapshot.latest!.status, .completed)
        XCTAssertNil(repository.snapshot.latest!.alarmObservedAt)
    }
    func testOfflineWatchDoesNotPreventPhoneReservation() async throws {
        bridge.failure = true
        let session = try await start(); clock.date = session.startedAt.addingTimeInterval(3600)
        try await coordinator.receive(receipt(session)); XCTAssertTrue(repository.snapshot.latest!.targetReserved)
    }
    func testConcurrentDuplicateReceiptsProduceOneOnset() async throws {
        let session = try await start(); clock.date = session.startedAt.addingTimeInterval(3600)
        let coordinator = coordinator!, event = receipt(session)
        try await withThrowingTaskGroup(of: Void.self) { group in
            for _ in 0..<12 { group.addTask { try await coordinator.receive(event) } }
            try await group.waitForAll()
        }
        XCTAssertEqual(repository.snapshot.events.filter { $0.kind == .onset }.count, 1)
        XCTAssertEqual(scheduler.alarms.count, 1)
    }
    func testBackupThatActuallyRingsAfterFailedReplacementIsRecorded() async throws {
        let session = try await start(); scheduler.cancellationFailure = true
        clock.date = session.startedAt.addingTimeInterval(3600)
        try await coordinator.receive(receipt(session))
        XCTAssertTrue(repository.snapshot.latest!.targetReserved)
        try await coordinator.observeAlert(alarmID: session.backupAlarmID, observedAt: clock.date)
        XCTAssertEqual(repository.snapshot.latest!.firedKind, .backup)
        XCTAssertEqual(repository.snapshot.latest!.status, .ringing)
    }
    func testStopStillWorksAfterCancellationFailed() async throws {
        let session = try await start(); scheduler.cancellationFailure = true
        try await coordinator.cancel()
        scheduler.cancellationFailure = false
        try await coordinator.dismiss(alarmID: session.backupAlarmID)
        XCTAssertEqual(repository.snapshot.latest!.status, .completed)
        XCTAssertFalse(repository.snapshot.latest!.hasReservations)
    }
    func testRestoreRepairsBackupFlagAfterSystemAcceptedBeforePersistence() async throws {
        var session = try await start(); session.backupReserved = false
        try repository.save(session)
        try await coordinator.restore()
        XCTAssertTrue(repository.snapshot.latest!.backupReserved)
    }
    func testPastReservationIsCancelledAndFailureIsNotHidden() async throws {
        let session = try await start(backup: false)
        clock.date = session.startedAt.addingTimeInterval(3600)
        try await coordinator.receive(receipt(session))
        clock.date = repository.snapshot.latest!.alarmAt!.addingTimeInterval(60)
        scheduler.cancellationFailure = true
        try await coordinator.restore()
        XCTAssertTrue(repository.snapshot.latest!.targetReserved)
        XCTAssertNotNil(repository.snapshot.latest!.failure)
        scheduler.cancellationFailure = false
        try await coordinator.restore()
        XCTAssertFalse(repository.snapshot.latest!.targetReserved)
        XCTAssertTrue(scheduler.alarms.isEmpty)
    }
}
