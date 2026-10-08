import SwiftUI
import UIKit
import WakeCore

struct SettingsView: View {
    @ObservedObject var model: AppModel
    var body: some View {
        List {
            Section("알람") {
                Button(action: model.openSystemSettings) { Label { HStack { Text("알람"); Spacer(); Text(model.alarmAllowed ? "허용됨" : "허용 필요").foregroundStyle(.secondary) } } icon: { Image(systemName: "bell") } }
                Button { Task { await model.selectHealthData() } } label: {
                    Label { HStack { Text("수면 기록"); Spacer(); Text(model.snapshot.settings.healthRequested ? "선택 완료" : "선택 필요").foregroundStyle(.secondary) } } icon: { Image(systemName: "heart.text.square") }
                }
            }
            Section("기기") {
                LabeledContent { Text(model.paired ? "연동됨" : "연동 없음") } label: { Label("Apple Watch", systemImage: "applewatch") }
                Button { Task { await model.recover() } } label: { Label("기록·연결 새로고침", systemImage: "arrow.clockwise") }.disabled(model.busy)
            }
            Section("기록") {
                NavigationLink { DiagnosticView(model: model) } label: { Label("진단 로그", systemImage: "list.bullet.rectangle") }
                ExportButton(model: model)
            }
            Section {
                LabeledContent("버전", value: Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "")
            } footer: { Text("건강 기록의 전달 시점은 기기와 동기화 상태에 따라 달라집니다.") }
        }.listStyle(.insetGrouped).navigationTitle("설정")
    }
}
struct DiagnosticView: View {
    @ObservedObject var model: AppModel
    var body: some View {
        List {
            if model.snapshot.events.isEmpty { Text("아직 기록이 없어요.").foregroundStyle(.secondary) }
            ForEach(Array(model.snapshot.events.suffix(150).reversed())) { event in
                DisclosureGroup {
                    VStack(alignment: .leading, spacing: 8) {
                        Text(SleepPresentation.day(event.at) + " · " + event.kind.rawValue).font(.caption).foregroundStyle(.secondary)
                        if !event.detail.isEmpty { Text(event.detail).font(.footnote).textSelection(.enabled) }
                        Text(event.sessionID?.uuidString ?? "").font(.caption2).foregroundStyle(.tertiary).textSelection(.enabled)
                    }.padding(.vertical, 6)
                } label: { HStack { Text(title(event.kind)).font(.subheadline); Spacer(); Text(SleepPresentation.time(event.at, seconds: true)).font(.caption).foregroundStyle(.secondary).monospacedDigit() } }
            }
        }.navigationTitle("진단 로그").navigationBarTitleDisplayMode(.inline)
            .toolbar { ExportButton(model: model) }
    }
    private func title(_ kind: EventKind) -> String {
        switch kind {
        case .started: "감시 시작"
        case .onset: "입면"
        case .received: "기록 수신"
        case .reserved: "알람 예약"
        case .alarmObserved: "알람 울림 확인"
        case .dismissed: "알람 해제"
        case .cancelled: "감시 종료"
        case .missed: "기상 시각 경과"
        case .restored: "예약 확인"
        case .ignored: "중복·이전 정보"
        case .scheduleFailed: "예약 확인 필요"
        case .cancellationFailed: "해제 확인 필요"
        case .healthError: "기록 수신 확인"
        case .transportError: "워치 전달 대기"
        }
    }
}
struct ExportButton: View {
    @ObservedObject var model: AppModel
    @State private var file: ExportFile?
    var body: some View {
        Button {
            do { file = ExportFile(url: try model.exportCSV()) } catch { model.message = error.localizedDescription }
        } label: { Label("기록 내보내기", systemImage: "square.and.arrow.up") }
            .sheet(item: $file) { item in ShareSheet(url: item.url).presentationDetents([.medium, .large]) }
    }
}
private struct ExportFile: Identifiable { let id = UUID(); let url: URL }
private struct ShareSheet: UIViewControllerRepresentable {
    let url: URL
    func makeUIViewController(context: Context) -> UIActivityViewController {
        let controller = UIActivityViewController(activityItems: [url], applicationActivities: nil)
        if let popover = controller.popoverPresentationController { popover.sourceView = controller.view; popover.sourceRect = CGRect(x: controller.view.bounds.midX, y: controller.view.bounds.midY, width: 1, height: 1) }
        return controller
    }
    func updateUIViewController(_ controller: UIActivityViewController, context: Context) { }
}
