import SwiftUI
import WakeCore

struct TonightView: View {
    @ObservedObject var model: AppModel
    let openFlow: () -> Void
    @State private var durationSheet = false
    @State private var confirmStop = false
    private var active: Bool { model.session?.isActive == true }
    var body: some View {
        ScrollView {
            VStack(spacing: 22) {
                HStack { Text(SleepPresentation.day(Date())).font(.subheadline).foregroundStyle(.secondary); Spacer() }
                deviceBadge
                VStack(spacing: 12) {
                    MoonHalo(phase: model.phase)
                    Text(headline).font(.title3.weight(.semibold)).foregroundStyle(Palette.phase(model.phase))
                    Text(heroValue).font(.system(size: model.phase == .reserved || model.phase == .ringing ? 64 : 43, weight: .light, design: .rounded))
                        .monospacedDigit().minimumScaleFactor(0.65).lineLimit(1).contentTransition(.numericText())
                    Text(subtitle).font(.subheadline).foregroundStyle(.secondary)
                }.padding(.vertical, 8).frame(maxWidth: .infinity)
                if !active && model.session?.hasReservations != true {
                    HStack(spacing: 10) {
                        ForEach([360, 450, 540], id: \.self) { minutes in
                            let selected = model.snapshot.settings.targetMinutes == minutes
                            Button { model.updateSettings(targetMinutes: minutes) } label: {
                                Text(SleepPresentation.duration(minutes)).font(.caption.weight(.semibold)).lineLimit(1).minimumScaleFactor(0.7).frame(maxWidth: .infinity).padding(.vertical, 16)
                            }.buttonStyle(.plain).foregroundStyle(selected ? Palette.accent : .secondary)
                                .background(selected ? Palette.accent.opacity(0.12) : Palette.surface, in: RoundedRectangle(cornerRadius: 18))
                                .overlay(RoundedRectangle(cornerRadius: 18).strokeBorder(selected ? Palette.accent.opacity(0.35) : .clear))
                        }
                    }
                    Button("시간 직접 설정") { durationSheet = true }.font(.caption).foregroundStyle(.secondary)
                }
                backupCard
                if let failure = model.session?.failure {
                    Label(failure, systemImage: "exclamationmark.circle").font(.footnote).foregroundStyle(.orange).frame(maxWidth: .infinity, alignment: .leading)
                    Button("다시 확인") { Task { await model.recover() } }.font(.subheadline)
                }
                if !model.observing && model.phase != .reserved && model.phase != .ringing {
                    Label("수면 기록 수신을 확인해 주세요.", systemImage: "heart.text.square").font(.footnote).foregroundStyle(.orange)
                    Button("다시 연결") { Task { await model.recover() } }
                }
                if model.session?.onsetAt != nil {
                    Button(action: openFlow) { Label("수면 흐름 보기", systemImage: "arrow.right").font(.subheadline) }.padding(.top, 4)
                }
            }.padding(24).frame(maxWidth: 560).frame(maxWidth: .infinity)
        }.background(Palette.canvas).navigationTitle("오늘 밤")
            .safeAreaInset(edge: .bottom) { primaryAction.padding(.horizontal, 24).padding(.vertical, 12).frame(maxWidth: 560).frame(maxWidth: .infinity).background(.ultraThinMaterial) }
            .sheet(isPresented: $durationSheet) { DurationSheet(minutes: model.snapshot.settings.targetMinutes) { model.updateSettings(targetMinutes: $0) } }
            .confirmationDialog("감시를 끝낼까요?", isPresented: $confirmStop, titleVisibility: .visible) {
                Button("감시 끝내기", role: .destructive) { Task { await model.cancel() } }
                Button("계속 감시", role: .cancel) { }
            } message: { Text("오늘 예약한 알람도 해제됩니다.") }
    }
    private var headline: String {
        switch model.phase {
        case .ready: "오늘 밤 준비"
        case .preparing: model.session?.status == .scheduling ? "알람 예약 중" : "수면 기록 연결 중"
        case .waiting: "입면 대기"
        case .reserved: "기상 알람 예약됨"
        case .ringing: "일어날 시간"
        case .attention: "확인 필요"
        }
    }
    private var heroValue: String {
        if model.phase == .reserved || model.phase == .ringing {
            return SleepPresentation.time(model.session?.firedKind == .backup ? model.session?.backupAt : model.session?.alarmAt)
        }
        return SleepPresentation.duration(active ? model.session!.targetMinutes : model.snapshot.settings.targetMinutes)
    }
    private var subtitle: String {
        switch model.phase {
        case .ready: "자기 전에 한 번 시작"
        case .preparing: model.session?.onsetAt != nil ? "입면 \(SleepPresentation.time(model.session?.onsetAt))" : "건강 기록 수신 준비 중"
        case .waiting: "잠든 시각부터 자동 예약"
        case .reserved: "입면 \(SleepPresentation.time(model.session?.onsetAt))"
        case .ringing: "알람을 끄고 오늘을 시작해요"
        case .attention: model.session?.backupReserved == true ? "예비 알람 유지" : "아래 상태를 확인해 주세요"
        }
    }
    private var deviceBadge: some View {
        Button { Task { await model.recover() } } label: {
            HStack(spacing: 14) {
                let ready = model.paired && active && model.watchReport?.observing == true && model.watchReport?.sessionID == model.session?.id
                Image(systemName: model.paired ? "applewatch" : "heart.text.square.fill").font(.title2)
                    .foregroundStyle(ready ? Palette.mint : Palette.accent).frame(width: 46, height: 46)
                    .background((ready ? Palette.mint : Palette.accent).opacity(0.09), in: Circle())
                    .shadow(color: ready ? Palette.mint.opacity(0.22) : .clear, radius: 10)
                VStack(alignment: .leading, spacing: 4) {
                    Text(model.paired ? "Apple Watch" : "건강 기록").font(.subheadline.weight(.medium))
                    Text(model.paired ? (ready ? "감시 등록됨" : active ? "워치 확인 중" : "연동됨") : model.session?.onsetAt != nil ? "입면 기록 받음" : "수면 기록 대기").font(.caption).foregroundStyle(ready ? Palette.mint : .secondary)
                }
                Spacer(); Image(systemName: "arrow.clockwise").font(.caption).foregroundStyle(.tertiary)
            }.padding(16).background(Palette.surface, in: RoundedRectangle(cornerRadius: 24))
        }.buttonStyle(.plain).disabled(model.busy).accessibilityHint("기록과 연결 상태를 다시 확인합니다")
    }
    private var backupCard: some View {
        SoftCard {
            HStack(spacing: 14) {
                Image(systemName: "bell.badge").font(.title2).foregroundStyle(.orange)
                VStack(alignment: .leading, spacing: 7) {
                    Text("예비 알람").font(.caption).foregroundStyle(.secondary)
                    if active {
                        Text(model.session?.backupReserved == true ? SleepPresentation.time(model.session?.backupAt) : model.session?.targetReserved == true ? "기상 알람으로 전환" : "꺼짐").font(.headline).monospacedDigit()
                    } else if model.snapshot.settings.backupMinutes != nil {
                        DatePicker("예비 알람 시각", selection: backupDate, displayedComponents: .hourAndMinute).labelsHidden()
                    } else { Text("꺼짐").font(.headline) }
                }
                Spacer()
                if !active {
                    Toggle("예비 알람", isOn: Binding(get: { model.snapshot.settings.backupMinutes != nil }, set: { model.updateSettings(backupMinutes: $0 ? 420 : nil, changeBackup: true) }))
                        .labelsHidden().tint(.orange)
                }
            }
        }
    }
    private var backupDate: Binding<Date> {
        Binding(get: {
            let minutes = model.snapshot.settings.backupMinutes ?? 420
            return Calendar.current.date(bySettingHour: minutes / 60, minute: minutes % 60, second: 0, of: Date()) ?? Date()
        }, set: { date in
            let components = Calendar.current.dateComponents([.hour, .minute], from: date)
            model.updateSettings(backupMinutes: (components.hour ?? 7) * 60 + (components.minute ?? 0), changeBackup: true)
        })
    }
    @ViewBuilder private var primaryAction: some View {
        if model.phase == .ringing {
            actionButton("알람 끄기", icon: "bell.fill", enabled: !model.busy) { Task { await model.stopAlarm() } }
        } else if active || model.session?.hasReservations == true {
            Button { confirmStop = true } label: { Label(model.session?.isActive == true ? "감시 끝내기" : "알람 해제 다시 시도", systemImage: "stop.fill").font(.subheadline.weight(.medium)).frame(maxWidth: .infinity).padding(.vertical, 10) }
                .buttonStyle(.glass).buttonBorderShape(.capsule).controlSize(.large).disabled(model.busy)
        } else { actionButton("수면 감시 시작", icon: "play.fill", enabled: model.canStart) { Task { await model.start() } } }
    }
    private func actionButton(_ title: String, icon: String, enabled: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 10) { if model.busy { ProgressView() } else { Image(systemName: icon) }; Text(title).fontWeight(.semibold) }.frame(maxWidth: .infinity).padding(.vertical, 10)
        }.buttonStyle(.glassProminent).buttonBorderShape(.capsule).controlSize(.large).disabled(!enabled)
    }
}
private struct DurationSheet: View {
    @Environment(\.dismiss) private var dismiss
    @State private var hours: Int
    @State private var minutes: Int
    let save: (Int) -> Void
    init(minutes: Int, save: @escaping (Int) -> Void) { _hours = State(initialValue: minutes / 60); _minutes = State(initialValue: minutes % 60); self.save = save }
    var body: some View {
        NavigationStack {
            HStack {
                Picker("시간", selection: $hours) { ForEach(6...24, id: \.self) { Text("\($0)시간").tag($0) } }
                Picker("분", selection: $minutes) { ForEach(0...59, id: \.self) { Text("\($0)분").tag($0) } }
            }.pickerStyle(.wheel).padding().navigationTitle("목표 수면 시간").navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .cancellationAction) { Button("취소") { dismiss() } }; ToolbarItem(placement: .confirmationAction) { Button("저장") { save(hours * 60 + minutes); dismiss() } } }
        }.presentationDetents([.medium])
    }
}
