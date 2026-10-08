import SwiftUI
import WatchKit
import WakeCore

@MainActor final class WatchModel: ObservableObject {
    @Published private(set) var snapshot: WatchSnapshot
    @Published var observing = false
    @Published var busy = false
    @Published var error: String?
    let startupError: String?
    private let file: URL
    init(file: URL) {
        self.file = file
        do {
            if FileManager.default.fileExists(atPath: file.path) { snapshot = try WakeCoding.decode(WatchSnapshot.self, from: Data(contentsOf: file)) }
            else { snapshot = WatchSnapshot() }
            startupError = nil
        } catch { snapshot = WatchSnapshot(); startupError = error.localizedDescription; self.error = "기록을 열 수 없어요." }
    }
    func commit(_ snapshot: WatchSnapshot) throws {
        guard startupError == nil else { throw WakeError.storage(startupError!) }
        try FileManager.default.createDirectory(at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
        try WakeCoding.encode(snapshot).write(to: file, options: .atomic)
        try? FileManager.default.setAttributes([.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication], ofItemAtPath: file.path)
        var resource = file; var values = URLResourceValues(); values.isExcludedFromBackup = true; try? resource.setResourceValues(values)
        self.snapshot = snapshot
    }
}
@MainActor final class WatchRuntime {
    static let shared = WatchRuntime()
    let model: WatchModel
    let health = HealthSleepReader()
    let transport = WatchTransport()
    private var launched = false
    private var triedPermission = false
    private init() {
        let directory = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("wakemeup")
        model = WatchModel(file: directory.appendingPathComponent("watch.json"))
        health.watchID = model.snapshot.watchID
        health.currentCommand = { [weak self] in
            guard let self, var command = self.model.snapshot.command else { return nil }
            if let first = self.model.snapshot.firstReceipt, first.sessionID == command.sessionID { command.onsetAt = first.onsetAt }
            return command
        }
        health.onStatus = { [weak self] observing in self?.model.observing = observing; self?.report() }
        health.onError = { [weak model] _ in model?.error = "수면 기록 수신 확인 필요" }
        health.onReceipt = { [weak self] receipt in
            guard let self else { return }
            var next = self.model.snapshot
            if next.accept(receipt) { try self.model.commit(next); self.flushOutbox() }
        }
        transport.onChange = { [weak self] in self?.report(); self?.flushOutbox() }
        transport.onError = { [weak model] _ in model?.error = "iPhone 연결 대기" }
        transport.onEnvelope = { [weak self] envelope in
            guard let self else { return nil }
            do {
                var next = self.model.snapshot
                if next.apply(envelope) {
                    try self.model.commit(next)
                    if next.healthRequested { try await self.health.enableBackgroundDelivery() }
                    await self.health.scan(); self.report(); self.flushOutbox()
                }
            } catch { self.model.error = error.localizedDescription }
            return nil
        }
    }
    func launch() {
        guard !launched else { return }; launched = true
        health.installObserver(); transport.activate()
        if model.snapshot.healthRequested { Task { do { try await health.enableBackgroundDelivery(); await health.scan() } catch { model.error = "수면 기록 수신 확인 필요" } } }
    }
    func requestOnce() async {
        guard !triedPermission, !model.snapshot.healthRequested, model.startupError == nil else { return }
        triedPermission = true; await requestAccess()
    }
    func requestAccess() async {
        guard !model.busy else { return }; model.busy = true; defer { model.busy = false }
        do {
            try await health.requestAccess()
            var next = model.snapshot; next.healthRequested = true
            try model.commit(next); model.observing = health.observing; model.error = nil
            await health.scan(); report()
        } catch { model.error = "수면 기록 연결을 확인해 주세요." }
    }
    func refresh() async {
        guard !model.busy else { return }; model.busy = true; defer { model.busy = false }
        if model.snapshot.healthRequested {
            do { try await health.enableBackgroundDelivery(); model.observing = health.observing; model.error = nil }
            catch { model.error = "수면 기록 수신 확인 필요" }
        }
        await health.scan(); report(); flushOutbox()
    }
    private func report() {
        let report = WatchReport(watchID: model.snapshot.watchID, name: "Apple Watch", healthAvailable: health.available,
                                 healthRequested: model.snapshot.healthRequested, observing: health.observing && model.snapshot.command?.active == true,
                                 sessionID: model.snapshot.command?.sessionID, at: Date())
        try? transport.sendContext(SyncEnvelope(report: report))
    }
    private func flushOutbox() { for receipt in model.snapshot.outbox { try? transport.queue(SyncEnvelope(receipt: receipt)) } }
}
