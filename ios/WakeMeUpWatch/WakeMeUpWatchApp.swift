import SwiftUI
import WatchKit
import WakeCore

@MainActor final class WatchAppDelegate: NSObject, WKApplicationDelegate {
    private var tasks: [WKWatchConnectivityRefreshBackgroundTask] = []
    func applicationDidFinishLaunching() {
        WatchRuntime.shared.transport.onDrained = { [weak self] in self?.finishConnectivityTasks() }
        WatchRuntime.shared.launch()
    }
    func handle(_ backgroundTasks: Set<WKRefreshBackgroundTask>) {
        for task in backgroundTasks {
            if let connectivity = task as? WKWatchConnectivityRefreshBackgroundTask { tasks.append(connectivity) }
            else if let snapshot = task as? WKSnapshotRefreshBackgroundTask { snapshot.setTaskCompleted(restoredDefaultState: true, estimatedSnapshotExpiration: .distantFuture, userInfo: nil) }
            else { Task { await WatchRuntime.shared.health.scan(); task.setTaskCompletedWithSnapshot(false) } }
        }
        finishConnectivityTasks()
    }
    private func finishConnectivityTasks() {
        guard !WatchRuntime.shared.transport.hasPendingWork else { return }
        for task in tasks { task.setTaskCompletedWithSnapshot(false) }
        tasks.removeAll()
    }
}
@main struct WakeMeUpWatchApp: App {
    @WKApplicationDelegateAdaptor(WatchAppDelegate.self) private var delegate
    @StateObject private var model = WatchRuntime.shared.model
    var body: some Scene { WindowGroup { WatchHomeView(model: model) } }
}
struct WatchHomeView: View {
    @ObservedObject var model: WatchModel
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.scenePhase) private var scenePhase
    private var onset: Date? { model.snapshot.command?.onsetAt ?? model.snapshot.firstReceipt?.onsetAt }
    private var active: Bool { model.snapshot.command?.active == true }
    private var accent: Color { active && onset == nil && model.observing ? Color(red: 0.5, green: 0.87, blue: 0.73) : Color(red: 0.75, green: 0.69, blue: 1) }
    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 12) {
                    Image(systemName: onset == nil ? "moon.stars.fill" : "moon.zzz.fill")
                        .font(.system(size: 44, weight: .light)).foregroundStyle(accent.gradient).padding(.top, 12)
                        .symbolEffect(.pulse, options: .repeating, isActive: active && onset == nil && model.observing && !reduceMotion && scenePhase == .active)
                    Text(headline).font(.headline).foregroundStyle(accent)
                    if let onset {
                        Text(SleepPresentation.time(onset)).font(.system(size: 35, weight: .light, design: .rounded)).monospacedDigit()
                        if let alarm = model.snapshot.command?.alarmAt { Text("기상 \(SleepPresentation.time(alarm))").font(.caption).foregroundStyle(.secondary) }
                    } else if let command = model.snapshot.command, active { Text(SleepPresentation.duration(command.targetMinutes)).font(.title3).monospacedDigit() }
                    else { Text("iPhone에서 시작").font(.caption).foregroundStyle(.secondary) }
                    if let error = model.error { Text(error).font(.caption2).foregroundStyle(.orange).multilineTextAlignment(.center) }
                    if !model.snapshot.healthRequested {
                        Button("수면 기록 연결") { Task { await WatchRuntime.shared.requestAccess() } }.buttonStyle(.borderedProminent).tint(accent).disabled(model.busy)
                    } else {
                        Button { Task { await WatchRuntime.shared.refresh() } } label: { Label("다시 확인", systemImage: "arrow.clockwise").font(.caption) }.disabled(model.busy)
                    }
                    Text("알람은 iPhone에서").font(.caption2).foregroundStyle(.secondary).padding(.bottom, 8)
                }.frame(maxWidth: .infinity).padding(.horizontal, 12)
            }.navigationTitle("오늘 밤")
        }.task { await WatchRuntime.shared.requestOnce() }
            .onChange(of: scenePhase) { _, phase in if phase == .active { Task { await WatchRuntime.shared.refresh() } } }
    }
    private var headline: String {
        if !model.snapshot.healthRequested { return "수면 기록 연결" }
        if onset != nil { return "입면 기록됨" }
        if active { return model.observing ? "입면 대기" : "수신 준비 중" }
        return "오늘 밤 준비"
    }
}
