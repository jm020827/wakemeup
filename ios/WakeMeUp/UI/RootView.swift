import SwiftUI

enum AppPage: String, CaseIterable, Identifiable {
    case tonight, flow, settings
    var id: String { rawValue }
    var title: String { switch self { case .tonight: "오늘 밤"; case .flow: "수면 흐름"; case .settings: "설정" } }
    var icon: String { switch self { case .tonight: "moon.stars"; case .flow: "clock"; case .settings: "gearshape" } }
}
struct RootView: View {
    @ObservedObject var model: AppModel
    @State private var page: AppPage? = .tonight
    @Environment(\.horizontalSizeClass) private var width
    @Environment(\.scenePhase) private var scenePhase
    var body: some View {
        Group {
            if let error = model.startupError {
                ContentUnavailableView {
                    Label("기록을 열 수 없어요", systemImage: "externaldrive.badge.exclamationmark")
                } description: { Text(error) } actions: { ShareLink("원본 기록 내보내기", item: model.archiveURL) }
            } else if !model.permissionsChecked { ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity).background(Palette.canvas) }
            else if model.needsSetup { PermissionSetupView(model: model) }
            else if width == .regular {
                NavigationSplitView {
                    List(AppPage.allCases, selection: $page) { item in Label(item.title, systemImage: item.icon).tag(item) }
                        .navigationTitle("wakemeup")
                } detail: { NavigationStack { screen(page ?? .tonight) } }
            } else {
                TabView(selection: Binding(get: { page ?? .tonight }, set: { page = $0 })) {
                    ForEach(AppPage.allCases) { item in
                        Tab(item.title, systemImage: item.icon, value: item) { NavigationStack { screen(item) } }
                    }
                }
            }
        }
        .task { await model.prepareIfNeeded() }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { Task { await model.prepareIfNeeded(); await model.recover() } }
        }
        .alert("확인해 주세요", isPresented: Binding(get: { model.message != nil }, set: { if !$0 { model.message = nil } })) {
            Button("확인", role: .cancel) { model.message = nil }
        } message: { Text(model.message ?? "") }
    }
    @ViewBuilder private func screen(_ page: AppPage) -> some View {
        switch page {
        case .tonight: TonightView(model: model, openFlow: { self.page = .flow })
        case .flow: SleepFlowView(model: model)
        case .settings: SettingsView(model: model)
        }
    }
}
struct PermissionSetupView: View {
    @ObservedObject var model: AppModel
    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 24) {
                    Image(systemName: "bell.badge.fill").font(.system(size: 52)).foregroundStyle(Palette.accent.gradient).padding(.top, 36)
                    Text("오늘 밤을 준비해요").font(.largeTitle.bold())
                    Text("수면 기록을 읽고, 정한 시각에 깨워요.").font(.subheadline).foregroundStyle(.secondary)
                    SoftCard {
                        VStack(spacing: 22) {
                            permission("알람", subtitle: "잠금 화면에서도 깨우기", icon: "bell", completed: model.alarmAllowed, done: "허용됨")
                            Divider()
                            permission("수면 기록", subtitle: "건강 앱의 잠든 시각 읽기", icon: "heart.text.square", completed: model.snapshot.settings.healthRequested, done: "선택 완료")
                        }
                    }
                    Button { Task { await model.preparePermissions() } } label: {
                        HStack { if model.busy { ProgressView() }; Text(model.alarmAllowed ? "수면 기록 연결" : "알람 허용").fontWeight(.semibold) }.frame(maxWidth: .infinity).padding(.vertical, 10)
                    }.buttonStyle(.glassProminent).buttonBorderShape(.capsule).controlSize(.large).disabled(model.busy)
                    Text("선택한 수면 기록만 읽어요.").font(.footnote).foregroundStyle(.secondary)
                }.padding(28).frame(maxWidth: 520, alignment: .leading).frame(maxWidth: .infinity)
            }.background(Palette.canvas)
        }
    }
    private func permission(_ title: String, subtitle: String, icon: String, completed: Bool, done: String) -> some View {
        HStack(spacing: 16) {
            Image(systemName: completed ? "checkmark.circle.fill" : icon).font(.title2).foregroundStyle(completed ? Palette.mint : Palette.accent).frame(width: 32)
            VStack(alignment: .leading, spacing: 5) { Text(title).font(.headline); Text(subtitle).font(.caption).foregroundStyle(.secondary) }
            Spacer(); Text(completed ? done : "필요").font(.caption).foregroundStyle(completed ? Palette.mint : .secondary)
        }
    }
}
