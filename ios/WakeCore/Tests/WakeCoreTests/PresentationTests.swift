import Foundation
import XCTest
@testable import WakeCore

final class PresentationTests: XCTestCase {
    func testElapsedTimeAcrossDSTStillMeansSixHours() throws {
        let onset = ISO8601DateFormatter().date(from: "2025-03-09T06:30:00Z")!
        let alarm = try WakeMath.alarmAt(onset: onset, minutes: 360)
        XCTAssertEqual(alarm.timeIntervalSince(onset), 6 * 3600)
        XCTAssertEqual(SleepPresentation.time(alarm, timeZone: TimeZone(identifier: "America/New_York")!), "08:30")
    }
    func testBackupIsNextLocalTimeAndAlwaysFuture() throws {
        let now = ISO8601DateFormatter().date(from: "2025-01-14T23:00:00Z")!
        let alarm = try WakeMath.nextBackup(now: now, minutes: 420, timeZone: TimeZone(identifier: "Asia/Seoul")!)
        XCTAssertEqual(ISO8601DateFormatter().string(from: alarm), "2025-01-15T22:00:00Z")
    }
    func testFractionalStoredTimestampsRoundTrip() throws {
        let date = Date(timeIntervalSince1970: 1_740_000_000.12345)
        var session = NightSession(startedAt: date, targetMinutes: 450)
        session.onsetAt = date.addingTimeInterval(600.9876); session.receivedAt = date.addingTimeInterval(1801.654)
        let decoded = try WakeCoding.decode(NightSession.self, from: WakeCoding.encode(session))
        XCTAssertEqual(decoded.onsetAt!.timeIntervalSince1970, session.onsetAt!.timeIntervalSince1970, accuracy: 0.0001)
        XCTAssertEqual(decoded.receivedAt!.timeIntervalSince1970, session.receivedAt!.timeIntervalSince1970, accuracy: 0.0001)
    }
    func testTimelineSeparatesPlansFromActualEventsAndDeduplicatesRestoration() {
        let start = Date(timeIntervalSince1970: 1_740_000_000)
        var session = NightSession(startedAt: start, targetMinutes: 450)
        session.onsetAt = start.addingTimeInterval(900); session.receivedAt = start.addingTimeInterval(2101)
        session.alarmAt = session.onsetAt!.addingTimeInterval(450 * 60); session.targetReserved = true; session.status = .scheduled
        let events = [NightEvent(sessionID: session.id, at: session.receivedAt!, kind: .reserved), NightEvent(sessionID: session.id, at: session.receivedAt!.addingTimeInterval(60), kind: .reserved)]
        let moments = SleepPresentation.moments(session: session, events: events, now: start.addingTimeInterval(3000))
        XCTAssertEqual(moments.filter { $0.kind == .reservation }.count, 1)
        XCTAssertTrue(moments.first { $0.kind == .planned }!.planned)
        XCTAssertFalse(moments.contains { $0.kind == .alarm })
        XCTAssertEqual(SleepPresentation.receiptDelay(session), "20분 1초")
    }
    func testPreviousRecordSurvivesEmptyNewNights() {
        let start = Date(timeIntervalSince1970: 1_740_000_000)
        var old = NightSession(startedAt: start, targetMinutes: 450); old.onsetAt = start.addingTimeInterval(1000); old.status = .cancelled
        let empty = (0..<15).map { index -> NightSession in
            var session = NightSession(startedAt: start.addingTimeInterval(Double(index + 1) * 3600), targetMinutes: 450)
            session.status = .cancelled; return session
        }
        XCTAssertEqual(SleepPresentation.visibleSessions(WakeSnapshot(sessions: empty + [old])), [old])
    }
    func testReservationRemainsVisibleWhenObservationStops() {
        var session = NightSession(startedAt: Date(), targetMinutes: 450)
        session.targetReserved = true; session.status = .scheduled
        XCTAssertEqual(SleepPresentation.phase(session: session, observing: false, alarmAllowed: true), .reserved)
    }
    func testFailedRecoveryDoesNotShowAnExpiredReservationAsSuccess() {
        var session = NightSession(startedAt: Date(), targetMinutes: 450)
        session.targetReserved = true; session.status = .failed
        session.failure = "지난 기상 알람 해제 확인 필요"
        XCTAssertEqual(SleepPresentation.phase(session: session, observing: true, alarmAllowed: true), .attention)
        session.targetReserved = false; session.status = .scheduling; session.failure = nil
        XCTAssertEqual(SleepPresentation.phase(session: session, observing: true, alarmAllowed: true), .preparing)
    }
    func testCSVQuotesMultilineAndUsesUTC() {
        let event = NightEvent(sessionID: nil, at: Date(timeIntervalSince1970: 0), kind: .healthError, detail: "first, \"second\"\nnext")
        let csv = SleepPresentation.csv([event])
        XCTAssertTrue(csv.contains("1970-01-01T00:00:00.000Z")); XCTAssertTrue(csv.contains("\"first, \"\"second\"\"\nnext\""))
    }
}
@MainActor final class RepositoryTests: XCTestCase {
    func testFileReopenKeepsHistoryAndSettings() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let file = directory.appendingPathComponent("state.json")
        let repository = try FileWakeRepository(file: file)
        var session = NightSession(startedAt: Date(timeIntervalSince1970: 1000.123), targetMinutes: 450)
        session.onsetAt = session.startedAt.addingTimeInterval(600)
        let snapshot = WakeSnapshot(settings: WakeSettings(targetMinutes: 540, backupMinutes: nil, healthRequested: true, onboardingComplete: true), sessions: [session])
        try repository.commit(snapshot)
        XCTAssertEqual(try FileWakeRepository(file: file).snapshot, snapshot)
    }
}
