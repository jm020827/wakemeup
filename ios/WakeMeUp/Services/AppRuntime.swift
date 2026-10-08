import Foundation
import SwiftUI
import UIKit
import WakeCore

@MainActor final class PhoneWatchBridge: WakeWatchBridging {
    let transport: WatchTransport
    private var latest: NightSession?
    init(transport: WatchTransport) { self.transport = transport }
    func sync(_ session: NightSession) throws {
        latest = session
        let defaults = UserDefaults.standard
        let revision = max(Int64(Date().timeIntervalSince1970 * 1000), Int64(defaults.double(forKey: "watchRevision")) + 1)
        defaults.set(Double(revision), forKey: "watchRevision")
        try transport.sendContext(SyncEnvelope(revision: revision, command: WatchCommand(session)))
    }
    func resend() { if let latest { try? sync(latest) } }
}
@MainActor final class AppModel: ObservableObject {
    @Published private(set) var snapshot: WakeSnapshot
    @Published var alarmAllowed = false
    @Published var observing = false
    @Published var permissionsChecked = false
    @Published var busy = false
    @Published var message: String?
    @Published var paired = false
    @Published var watchReport: WatchReport?
    let startupError: String?
    let archiveURL: URL
    private let repository: any WakeRepository
    private let alarm: AlarmService
    private let health: HealthSleepReader
    private let coordinator: NightCoordinator
    private var automaticSetupAttempted = false
    init(repository: any WakeRepository, alarm: AlarmService, health: HealthSleepReader, coordinator: NightCoordinator, archiveURL: URL, startupError: String?) {
        self.repository = repository; self.alarm = alarm; self.health = health; self.coordinator = coordinator
        self.archiveURL = archiveURL; self.startupError = startupError; snapshot = repository.snapshot
    }
    var session: NightSession? { snapshot.latest }
    var phase: NightPhase { SleepPresentation.phase(session: session, observing: observing, alarmAllowed: alarmAllowed) }
    var needsSetup: Bool { !alarmAllowed || !snapshot.settings.healthRequested || !snapshot.settings.onboardingComplete }
    var canStart: Bool { startupError == nil && alarmAllowed && snapshot.settings.healthRequested && observing && !busy && session?.isActive != true && !snapshot.sessions.contains { !$0.isActive && $0.hasReservations } }
    func receiveSnapshot(_ snapshot: WakeSnapshot) { self.snapshot = snapshot }
    func refreshPermissions() {
        alarmAllowed = alarm.authorized; observing = health.observing; permissionsChecked = true
    }
    func prepareIfNeeded() async {
        refreshPermissions()
        guard needsSetup, !automaticSetupAttempted, startupError == nil, !busy else { return }
        automaticSetupAttempted = true
        await preparePermissions(automatic: true)
    }
    func preparePermissions(automatic: Bool = false) async {
        await perform {
            if !self.alarm.authorized {
                if self.alarm.denied {
                    if !automatic { self.openSystemSettings() }
                    return
                }
                guard try await self.alarm.requestAccess() else { return }
            }
            if !self.repository.snapshot.settings.healthRequested {
                try await self.health.requestAccess()
                var next = self.repository.snapshot; next.settings.healthRequested = true
                try self.repository.commit(next)
            } else if !self.health.observing { try await self.health.enableBackgroundDelivery() }
            var next = self.repository.snapshot; next.settings.onboardingComplete = true
            try self.repository.commit(next); self.refreshPermissions()
        }
        refreshPermissions()
    }
    func start() async {
        await perform {
            guard self.canStartIgnoringBusy else { throw WakeError.alarmAccess }
            let settings = self.repository.snapshot.settings
            let backup = try settings.backupMinutes.map { try WakeMath.nextBackup(now: Date(), minutes: $0) }
            _ = try await self.coordinator.start(minutes: settings.targetMinutes, backupAt: backup, watchID: self.paired ? self.watchReport?.watchID : nil)
            await self.health.scan()
        }
    }
    private var canStartIgnoringBusy: Bool { alarmAllowed && snapshot.settings.healthRequested && observing && session?.isActive != true }
    func cancel() async { await perform { try await self.coordinator.cancel() } }
    func stopAlarm() async {
        guard let session else { return }
        let id = session.firedKind == .backup ? session.backupAlarmID : session.targetAlarmID
        await perform { try self.alarm.stop(id: id); try await self.coordinator.dismiss(alarmID: id) }
    }
    func recover() async {
        await perform {
            self.refreshPermissions()
            if self.snapshot.settings.healthRequested { try await self.health.enableBackgroundDelivery() }
            await self.alarm.checkCurrentAlerts()
            if self.alarm.authorized { try await self.coordinator.restore() }
            await self.health.scan(); self.refreshPermissions()
        }
    }
    func updateSettings(targetMinutes: Int? = nil, backupMinutes: Int? = nil, changeBackup: Bool = false) {
        guard session?.isActive != true else { return }
        do {
            var next = repository.snapshot
            if let targetMinutes { _ = try WakeMath.alarmAt(onset: Date(), minutes: targetMinutes); next.settings.targetMinutes = targetMinutes }
            if changeBackup {
                if let backupMinutes { _ = try WakeMath.nextBackup(now: Date(), minutes: backupMinutes) }
                next.settings.backupMinutes = backupMinutes
            }
            try repository.commit(next)
        } catch { message = error.localizedDescription }
    }
    func selectHealthData() async { await perform { try await self.health.requestAccess(); self.refreshPermissions() } }
    func openSystemSettings() { if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) } }
    func exportCSV() throws -> URL {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("wakemeup-reports")
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let file = directory.appendingPathComponent("wakemeup-log.csv")
        try SleepPresentation.csv(snapshot.events).write(to: file, atomically: true, encoding: .utf8)
        return file
    }
    private func perform(_ work: () async throws -> Void) async {
        guard !busy else { return }; busy = true; defer { busy = false }
        do { try await work() } catch { message = error.localizedDescription }
    }
}
@MainActor final class AppRuntime {
    static let shared = AppRuntime()
    let repository: any WakeRepository
    let coordinator: NightCoordinator
    let alarm = AlarmService()
    let health = HealthSleepReader()
    let transport = WatchTransport()
    let bridge: PhoneWatchBridge
    let model: AppModel
    private var launched = false
    private init() {
        let directory = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("wakemeup")
        let archive = directory.appendingPathComponent("nights.json")
        var startupError: String?
        do { repository = try FileWakeRepository(file: archive) }
        catch { startupError = error.localizedDescription; repository = UnavailableRepository(reason: error.localizedDescription) }
        bridge = PhoneWatchBridge(transport: transport)
        coordinator = NightCoordinator(repository: repository, scheduler: alarm, bridge: bridge)
        model = AppModel(repository: repository, alarm: alarm, health: health, coordinator: coordinator, archiveURL: archive, startupError: startupError)
        if let file = repository as? FileWakeRepository {
            file.onChange = { [weak model] snapshot in
                model?.receiveSnapshot(snapshot)
                try? FileManager.default.setAttributes([.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication], ofItemAtPath: archive.path)
                var resource = archive; var values = URLResourceValues(); values.isExcludedFromBackup = true
                try? resource.setResourceValues(values)
            }
        }
        health.currentCommand = { [weak self] in self?.repository.snapshot.latest.map(WatchCommand.init) }
        health.onReceipt = { [weak self] receipt in
            guard let self else { return }
            try await self.coordinator.receive(receipt)
        }
        health.onStatus = { [weak model] observing in model?.observing = observing }
        health.onError = { [weak self] detail in self?.record(.healthError, detail) }
        alarm.onAuthorization = { [weak model] allowed in model?.alarmAllowed = allowed }
        alarm.onAlert = { [weak self] id, at in do { try await self?.coordinator.observeAlert(alarmID: id, observedAt: at) } catch { self?.model.message = error.localizedDescription } }
        transport.onChange = { [weak self] in
            guard let self else { return }; self.model.paired = self.transport.paired
            if let session = self.repository.snapshot.latest { try? self.bridge.sync(session) }
        }
        transport.onError = { [weak self] detail in self?.record(.transportError, detail) }
        transport.onEnvelope = { [weak self] envelope in
            guard let self else { return nil }
            if let report = envelope.report { self.model.watchReport = report }
            if let receipt = envelope.receipt {
                do {
                    try await self.coordinator.receive(receipt)
                    let ack = SyncEnvelope(acknowledgedSampleID: receipt.sampleID)
                    try? self.transport.queue(ack)
                    return ack
                } catch { self.model.message = error.localizedDescription }
            }
            return nil
        }
    }
    func launch() {
        guard !launched else { return }; launched = true
        // Observer installation happens synchronously at application launch, before background callbacks.
        health.installObserver(); transport.activate(); alarm.beginObserving(); model.refreshPermissions()
        Task {
            if repository.snapshot.settings.healthRequested {
                do { try await health.enableBackgroundDelivery() } catch { record(.healthError, error.localizedDescription) }
            }
            await alarm.checkCurrentAlerts()
            if alarm.authorized { do { try await coordinator.restore() } catch { model.message = error.localizedDescription } }
        }
    }
    private func record(_ kind: EventKind, _ detail: String) {
        let recent = repository.snapshot.events.last
        if recent?.kind == kind && recent?.detail == detail { return }
        try? repository.record(sessionID: repository.snapshot.latest?.id, at: Date(), kind: kind, detail: detail)
    }
}
@MainActor private final class UnavailableRepository: WakeRepository {
    let snapshot = WakeSnapshot()
    let reason: String
    init(reason: String) { self.reason = reason }
    func commit(_ snapshot: WakeSnapshot) throws { throw WakeError.storage(reason) }
}
