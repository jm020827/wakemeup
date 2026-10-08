import Foundation
import XCTest
@testable import WakeCore

final class WatchStateTests: XCTestCase {
    func testOldCommandsCannotRestartCancelledMonitoring() {
        var session = NightSession(startedAt: Date(), targetMinutes: 450)
        var state = WatchSnapshot()
        XCTAssertTrue(state.apply(SyncEnvelope(revision: 10, command: WatchCommand(session))))
        session.status = .cancelled
        XCTAssertTrue(state.apply(SyncEnvelope(revision: 11, command: WatchCommand(session))))
        var old = session; old.status = .monitoring
        XCTAssertFalse(state.apply(SyncEnvelope(revision: 10, command: WatchCommand(old))))
        XCTAssertFalse(state.command!.active)
    }
    func testReceiptSurvivesSerializationUntilAcknowledged() throws {
        let session = NightSession(startedAt: Date(timeIntervalSince1970: 1000), targetMinutes: 450)
        var state = WatchSnapshot(command: WatchCommand(session))
        let receipt = SleepReceipt(sampleID: UUID(), sessionID: session.id, onsetAt: session.startedAt.addingTimeInterval(600), observedAt: session.startedAt.addingTimeInterval(1800), origin: .watchHealthKit)
        XCTAssertTrue(state.accept(receipt)); XCTAssertFalse(state.accept(receipt))
        var reopened = try WakeCoding.decode(WatchSnapshot.self, from: WakeCoding.encode(state))
        XCTAssertEqual(reopened.outbox, [receipt])
        reopened.apply(SyncEnvelope(acknowledgedSampleID: UUID())); XCTAssertEqual(reopened.outbox.count, 1)
        reopened.apply(SyncEnvelope(acknowledgedSampleID: receipt.sampleID)); XCTAssertTrue(reopened.outbox.isEmpty)
    }
    func testNewSessionClearsPreviousOutboxAndRejectsPreviousReceipt() {
        let first = NightSession(startedAt: Date(timeIntervalSince1970: 1000), targetMinutes: 450)
        var state = WatchSnapshot(command: WatchCommand(first))
        let old = SleepReceipt(sampleID: UUID(), sessionID: first.id, onsetAt: first.startedAt.addingTimeInterval(600), observedAt: first.startedAt.addingTimeInterval(1800), origin: .watchHealthKit)
        state.accept(old)
        let next = NightSession(startedAt: first.startedAt.addingTimeInterval(86400), targetMinutes: 450)
        state.apply(SyncEnvelope(revision: 20, command: WatchCommand(next)))
        XCTAssertTrue(state.outbox.isEmpty); XCTAssertFalse(state.accept(old))
        XCTAssertNil(state.firstReceipt)
    }
    func testAckCannotCauseTheSameOrLaterOnsetToBeQueuedAgain() throws {
        let session = NightSession(startedAt: Date(timeIntervalSince1970: 1000), targetMinutes: 450)
        var state = WatchSnapshot(command: WatchCommand(session))
        let receipt = SleepReceipt(sampleID: UUID(), sessionID: session.id, onsetAt: session.startedAt.addingTimeInterval(600), observedAt: session.startedAt.addingTimeInterval(1800), origin: .watchHealthKit)
        XCTAssertTrue(state.accept(receipt))
        state.apply(SyncEnvelope(acknowledgedSampleID: receipt.sampleID))
        state = try WakeCoding.decode(WatchSnapshot.self, from: WakeCoding.encode(state))
        XCTAssertTrue(state.outbox.isEmpty)
        XCTAssertFalse(state.accept(receipt))
        var later = receipt; later.sampleID = UUID(); later.onsetAt = receipt.onsetAt.addingTimeInterval(3600)
        XCTAssertFalse(state.accept(later))
        XCTAssertEqual(state.firstReceipt?.onsetAt, receipt.onsetAt)
        XCTAssertFalse(state.apply(SyncEnvelope(acknowledgedSampleID: receipt.sampleID)))
    }
}
