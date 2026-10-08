import Foundation

public struct WatchSnapshot: Codable, Equatable, Sendable {
    public var watchID: String
    public var healthRequested: Bool
    public var revision: Int64
    public var command: WatchCommand?
    public var outbox: [SleepReceipt]
    public var firstReceipt: SleepReceipt?
    public init(watchID: String = UUID().uuidString, healthRequested: Bool = false, revision: Int64 = 0, command: WatchCommand? = nil, outbox: [SleepReceipt] = [], firstReceipt: SleepReceipt? = nil) {
        self.watchID = watchID; self.healthRequested = healthRequested; self.revision = revision; self.command = command; self.outbox = outbox
        self.firstReceipt = firstReceipt ?? outbox.first
    }
    @discardableResult public mutating func apply(_ envelope: SyncEnvelope) -> Bool {
        guard envelope.version == 1 else { return false }
        var changed = false
        if let ack = envelope.acknowledgedSampleID {
            let count = outbox.count
            outbox.removeAll { $0.sampleID == ack }; changed = outbox.count != count
        }
        guard let incoming = envelope.command, envelope.revision >= revision else { return changed }
        if incoming.sessionID != command?.sessionID { firstReceipt = nil }
        if incoming.sessionID != command?.sessionID || !incoming.active || incoming.onsetAt != nil { outbox.removeAll() }
        command = incoming; revision = envelope.revision; return true
    }
    @discardableResult public mutating func accept(_ receipt: SleepReceipt) -> Bool {
        guard let command, command.active, command.onsetAt == nil, command.sessionID == receipt.sessionID, receipt.onsetAt >= command.startedAt, outbox.isEmpty, firstReceipt?.sessionID != receipt.sessionID else { return false }
        firstReceipt = receipt; outbox.append(receipt); return true
    }
}
