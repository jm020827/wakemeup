import Foundation
import WatchConnectivity
import WakeCore

final class WatchTransport: NSObject, WCSessionDelegate, @unchecked Sendable {
    private let session: WCSession?
    private let lock = NSLock()
    private var pending = 0
    private var contextObservation: NSKeyValueObservation?
    @MainActor var onEnvelope: ((SyncEnvelope) async -> SyncEnvelope?)?
    @MainActor var onChange: (() -> Void)?
    @MainActor var onDrained: (() -> Void)?
    @MainActor var onError: ((String) -> Void)?
    var supported: Bool { session != nil }
    var hasPendingWork: Bool {
        lock.lock(); defer { lock.unlock() }
        return pending > 0 || session?.hasContentPending == true || session?.activationState != .activated
    }
    @MainActor var paired: Bool {
        #if os(iOS)
        session?.isPaired == true && session?.isWatchAppInstalled == true
        #else
        session?.isCompanionAppInstalled == true
        #endif
    }
    @MainActor var reachable: Bool { session?.isReachable == true }
    override init() { session = WCSession.isSupported() ? WCSession.default : nil; super.init() }
    @MainActor func activate() {
        guard let session else { return }
        session.delegate = self
        contextObservation = session.observe(\.hasContentPending) { [weak self] _, _ in self?.notifyDrained() }
        session.activate()
    }
    @MainActor func sendContext(_ envelope: SyncEnvelope) throws {
        guard let session, session.activationState == .activated else { return }
        let data = try WakeCoding.encode(envelope)
        try session.updateApplicationContext(["payload": data])
        if session.isReachable { session.sendMessage(["payload": data], replyHandler: { [weak self] reply in self?.receive(reply) }, errorHandler: { _ in /* Application context remains queued. */ }) }
    }
    @MainActor func queue(_ envelope: SyncEnvelope) throws {
        guard let session, session.activationState == .activated else { return }
        let data = try WakeCoding.encode(envelope)
        let alreadyQueued = session.outstandingUserInfoTransfers.contains { transfer in
            guard let pending = transfer.userInfo["payload"] as? Data, let old = try? WakeCoding.decode(SyncEnvelope.self, from: pending) else { return false }
            return envelope.receipt?.sampleID != nil && old.receipt?.sampleID == envelope.receipt?.sampleID
        }
        if !alreadyQueued { session.transferUserInfo(["payload": data]) }
        if session.isReachable { session.sendMessage(["payload": data], replyHandler: { [weak self] reply in self?.receive(reply) }, errorHandler: { _ in }) }
    }
    private func receive(_ dictionary: [String: Any], reply: (([String: Any]) -> Void)? = nil) {
        guard let data = dictionary["payload"] as? Data, let envelope = try? WakeCoding.decode(SyncEnvelope.self, from: data), envelope.version == 1 else { reply?([:]); return }
        lock.lock(); pending += 1; lock.unlock()
        Task { @MainActor [weak self] in
            guard let self else { reply?([:]); return }
            let response = await self.onEnvelope?(envelope)
            if let response, let encoded = try? WakeCoding.encode(response) { reply?(["payload": encoded]) }
            else { reply?([:]) }
            self.finishWork()
        }
    }
    private func finishWork() {
        lock.lock(); pending -= 1; lock.unlock()
        notifyDrained()
    }
    private func notifyChanged() { Task { @MainActor [weak self] in self?.onChange?(); self?.onDrained?() } }
    private func notifyDrained() { Task { @MainActor [weak self] in self?.onDrained?() } }
    func session(_ session: WCSession, activationDidCompleteWith activationState: WCSessionActivationState, error: Error?) {
        if let error { Task { @MainActor [weak self] in self?.onError?(error.localizedDescription) } }
        if !session.receivedApplicationContext.isEmpty { receive(session.receivedApplicationContext) }
        notifyChanged()
    }
    func sessionReachabilityDidChange(_ session: WCSession) { notifyChanged() }
    func session(_ session: WCSession, didReceiveApplicationContext applicationContext: [String: Any]) { receive(applicationContext) }
    func session(_ session: WCSession, didReceiveUserInfo userInfo: [String: Any] = [:]) { receive(userInfo) }
    func session(_ session: WCSession, didReceiveMessage message: [String: Any], replyHandler: @escaping ([String: Any]) -> Void) { receive(message, reply: replyHandler) }
    func session(_ session: WCSession, didFinish userInfoTransfer: WCSessionUserInfoTransfer, error: Error?) {
        if let error { Task { @MainActor [weak self] in self?.onError?(error.localizedDescription) } }
        notifyDrained()
    }
    #if os(iOS)
    func sessionWatchStateDidChange(_ session: WCSession) { notifyChanged() }
    func sessionDidBecomeInactive(_ session: WCSession) { notifyChanged() }
    func sessionDidDeactivate(_ session: WCSession) { session.activate() }
    #endif
}
