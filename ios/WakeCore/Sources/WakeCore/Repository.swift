import Foundation

public enum WakeCoding {
    public static func encode<T: Encodable>(_ value: T) throws -> Data {
        let encoder = JSONEncoder(); encoder.dateEncodingStrategy = .millisecondsSince1970
        encoder.outputFormatting = [.sortedKeys]
        return try encoder.encode(value)
    }
    public static func decode<T: Decodable>(_ type: T.Type, from data: Data) throws -> T {
        let decoder = JSONDecoder(); decoder.dateDecodingStrategy = .millisecondsSince1970
        return try decoder.decode(type, from: data)
    }
}
@MainActor public protocol WakeRepository: AnyObject {
    var snapshot: WakeSnapshot { get }
    func commit(_ snapshot: WakeSnapshot) throws
}
@MainActor public extension WakeRepository {
    func save(_ session: NightSession) throws {
        var next = snapshot
        if let index = next.sessions.firstIndex(where: { $0.id == session.id }) { next.sessions[index] = session }
        else { next.sessions.insert(session, at: 0) }
        try commit(next)
    }
    func record(sessionID: UUID?, at: Date, kind: EventKind, detail: String = "") throws {
        var next = snapshot
        next.events.append(NightEvent(sessionID: sessionID, at: at, kind: kind, detail: detail))
        try commit(next)
    }
}
@MainActor public final class FileWakeRepository: WakeRepository {
    public private(set) var snapshot: WakeSnapshot
    public var onChange: ((WakeSnapshot) -> Void)?
    private let file: URL
    public init(file: URL) throws {
        self.file = file
        if FileManager.default.fileExists(atPath: file.path) {
            snapshot = try WakeCoding.decode(WakeSnapshot.self, from: Data(contentsOf: file))
        } else { snapshot = WakeSnapshot() }
    }
    public func commit(_ snapshot: WakeSnapshot) throws {
        try FileManager.default.createDirectory(at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
        try WakeCoding.encode(snapshot).write(to: file, options: .atomic)
        self.snapshot = snapshot; onChange?(snapshot)
    }
}
