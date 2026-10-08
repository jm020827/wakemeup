import Foundation
import HealthKit
import WakeCore

/// Observes stored sleep samples. HealthKit does not promise live sleep detection or expose read-denial status.
@MainActor final class HealthSleepReader {
    private let store = HKHealthStore()
    private let sleepType = HKObjectType.categoryType(forIdentifier: .sleepAnalysis)!
    private var observer: HKObserverQuery?
    private var scanning = false
    private var rescanRequested = false
    private var scanWaiters: [CheckedContinuation<Void, Never>] = []
    private var backgroundEnabled = false
    var currentCommand: (() -> WatchCommand?)?
    var onReceipt: ((SleepReceipt) async throws -> Void)?
    var onError: ((String) -> Void)?
    var onStatus: ((Bool) -> Void)?
    var observing: Bool { observer != nil && backgroundEnabled }
    var available: Bool { HKHealthStore.isHealthDataAvailable() }
    var watchID: String?

    func installObserver() {
        guard available, observer == nil else { return }
        let query = HKObserverQuery(sampleType: sleepType, predicate: nil) { [weak self] _, completion, error in
            Task { @MainActor in
                defer { completion() }
                guard let self else { return }
                if let error { self.onError?(error.localizedDescription); return }
                await self.scan()
            }
        }
        observer = query; store.execute(query)
    }
    func requestAccess() async throws {
        guard available else { throw ReaderError.unavailable }
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            store.requestAuthorization(toShare: [], read: [sleepType]) { completed, error in
                if let error { continuation.resume(throwing: error) }
                else if completed { continuation.resume() }
                else { continuation.resume(throwing: ReaderError.requestIncomplete) }
            }
        }
        // A completed request is not proof of read authorization. Empty queries remain private to the user.
        try await enableBackgroundDelivery()
    }
    func enableBackgroundDelivery() async throws {
        guard available else { return }
        installObserver()
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            store.enableBackgroundDelivery(for: sleepType, frequency: .immediate) { success, error in
                if let error { continuation.resume(throwing: error) }
                else if success { continuation.resume() }
                else { continuation.resume(throwing: ReaderError.backgroundUnavailable) }
            }
        }
        backgroundEnabled = true; onStatus?(true)
    }
    func scan() async {
        guard let command = currentCommand?(), command.active, command.onsetAt == nil else { return }
        if scanning {
            rescanRequested = true
            // Every HealthKit observer completion waits for all queued data to be processed.
            await withCheckedContinuation { scanWaiters.append($0) }
            return
        }
        scanning = true
        defer {
            scanning = false
            let waiting = scanWaiters; scanWaiters.removeAll()
            for waiter in waiting { waiter.resume() }
        }
        repeat { rescanRequested = false; await scanCurrentCommand() } while rescanRequested
    }
    private func scanCurrentCommand() async {
        guard let command = currentCommand?(), command.active, command.onsetAt == nil else { return }
        do {
            let samples = try await query(startedAt: command.startedAt)
            let values = Set([HKCategoryValueSleepAnalysis.asleepUnspecified.rawValue, HKCategoryValueSleepAnalysis.asleepCore.rawValue, HKCategoryValueSleepAnalysis.asleepDeep.rawValue, HKCategoryValueSleepAnalysis.asleepREM.rawValue])
            guard let sample = samples.first(where: { values.contains($0.value) && isWatchSample($0) && $0.startDate >= command.startedAt && $0.startDate <= Date() }) else { return }
            #if os(watchOS)
            let origin = ReceiptOrigin.watchHealthKit
            #else
            let origin = ReceiptOrigin.phoneHealthKit
            #endif
            let receipt = SleepReceipt(sampleID: sample.uuid, sessionID: command.sessionID, onsetAt: sample.startDate, observedAt: Date(), origin: origin, watchID: watchID)
            try await onReceipt?(receipt)
        } catch { onError?(error.localizedDescription) }
    }
    private func query(startedAt: Date) async throws -> [HKCategorySample] {
        try await withCheckedThrowingContinuation { continuation in
            let predicate = HKQuery.predicateForSamples(withStart: startedAt, end: Date(), options: .strictStartDate)
            let query = HKSampleQuery(sampleType: sleepType, predicate: predicate, limit: HKObjectQueryNoLimit,
                                      sortDescriptors: [NSSortDescriptor(key: HKSampleSortIdentifierStartDate, ascending: true)]) { _, samples, error in
                if let error { continuation.resume(throwing: error) }
                else { continuation.resume(returning: samples as? [HKCategorySample] ?? []) }
            }
            store.execute(query)
        }
    }
    private func isWatchSample(_ sample: HKCategorySample) -> Bool {
        let model = sample.device?.model?.lowercased() ?? ""
        let product = sample.sourceRevision.productType?.lowercased() ?? ""
        return model.contains("watch") || product.hasPrefix("watch")
    }
    private enum ReaderError: Error, LocalizedError {
        case unavailable, requestIncomplete, backgroundUnavailable
        var errorDescription: String? {
            switch self {
            case .unavailable: "이 기기에서는 건강 기록을 읽을 수 없어요."
            case .requestIncomplete: "수면 기록 선택을 완료해 주세요."
            case .backgroundUnavailable: "수면 기록의 백그라운드 수신을 확인해 주세요."
            }
        }
    }
}
