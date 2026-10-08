import SwiftUI
import WakeCore

struct SleepFlowView: View {
    @ObservedObject var model: AppModel
    @SceneStorage("selectedNightID") private var selectedNightID = ""
    @State private var selectedMomentID = "onset"
    private var sessions: [NightSession] { SleepPresentation.visibleSessions(model.snapshot) }
    private var selected: NightSession? {
        sessions.first { $0.id.uuidString == selectedNightID } ?? sessions.first { $0.onsetAt != nil || $0.alarmObservedAt != nil } ?? sessions.first
    }
    var body: some View {
        Group {
            if let session = selected {
                TimelineView(.periodic(from: .now, by: 30)) { timeline in
                    ScrollView {
                        VStack(spacing: 22) {
                            nightPicker
                            ViewThatFits(in: .horizontal) {
                                HStack(alignment: .top, spacing: 28) { summary(session, now: timeline.date).frame(width: 400); events(session, now: timeline.date).frame(width: 350) }.frame(minWidth: 778)
                                VStack(spacing: 24) { summary(session, now: timeline.date); events(session, now: timeline.date) }
                            }
                        }.padding(24).frame(maxWidth: 900).frame(maxWidth: .infinity)
                    }
                }
                .onChange(of: session.id) { _, _ in selectedMomentID = session.onsetAt == nil ? "start" : "onset" }
            } else {
                ContentUnavailableView { Label("아직 수면 기록이 없어요", systemImage: "moon.stars") } description: { Text("오늘 밤 감시를 시작해 보세요.") }
            }
        }.background(Palette.canvas).navigationTitle("수면 흐름")
    }
    private var nightPicker: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 9) {
                ForEach(sessions) { session in
                    let active = session.id == selected?.id
                    Button { selectedNightID = session.id.uuidString } label: {
                        Text(SleepPresentation.day(session.startedAt).components(separatedBy: " ·")[0] + "  " + SleepPresentation.time(session.startedAt))
                            .font(.caption.weight(.medium)).padding(.horizontal, 16).padding(.vertical, 12)
                    }.buttonStyle(.plain).foregroundStyle(active ? Palette.accent : .secondary)
                        .background(active ? Palette.accent.opacity(0.12) : Palette.surface, in: Capsule())
                        .overlay(Capsule().strokeBorder(active ? Palette.accent.opacity(0.3) : .clear))
                }
            }
        }
    }
    private func summary(_ session: NightSession, now: Date) -> some View {
        let moments = SleepPresentation.moments(session: session, events: model.snapshot.events, now: now)
        return VStack(spacing: 20) {
            SoftCard { SleepClock(session: session, moments: moments, now: now, selectedID: selectedMomentID, select: { selectedMomentID = $0 }) }
            HStack { legend("입면 대기", color: .secondary); Spacer(); legend("입면 이후", color: Palette.accent); Spacer(); legend("예정", color: Palette.accent.opacity(0.5)) }.font(.caption2)
            SoftCard {
                VStack(spacing: 17) {
                    MetricRow(title: "입면", value: SleepPresentation.time(session.onsetAt, seconds: true))
                    MetricRow(title: "기록 수신", value: SleepPresentation.time(session.receivedAt, seconds: true))
                    MetricRow(title: "수신 지연", value: SleepPresentation.receiptDelay(session))
                    MetricRow(title: "목표", value: SleepPresentation.duration(session.targetMinutes))
                }
            }
            if let failure = session.failure { Label(failure, systemImage: "exclamationmark.circle").font(.footnote).foregroundStyle(.orange) }
        }
    }
    private func legend(_ title: String, color: Color) -> some View { HStack(spacing: 6) { Circle().fill(color).frame(width: 6, height: 6); Text(title).foregroundStyle(.secondary) } }
    private func events(_ session: NightSession, now: Date) -> some View {
        let moments = SleepPresentation.moments(session: session, events: model.snapshot.events, now: now)
        return VStack(alignment: .leading, spacing: 16) {
            Text("밤사이 있었던 일").font(.headline).padding(.leading, 4)
            SoftCard {
                VStack(spacing: 4) {
                    ForEach(moments) { moment in
                        Button { selectedMomentID = moment.id } label: {
                            HStack(alignment: .top, spacing: 12) {
                                Circle().fill(moment.planned ? .clear : Palette.moment(moment.kind)).frame(width: 8, height: 8)
                                    .overlay(Circle().strokeBorder(Palette.moment(moment.kind), lineWidth: moment.planned ? 1.5 : 0)).padding(.top, 6)
                                VStack(alignment: .leading, spacing: 6) {
                                    Text(moment.title).font(.subheadline.weight(.medium)).foregroundStyle(moment.planned ? .secondary : .primary)
                                    if !moment.detail.isEmpty { Text(moment.detail).font(.caption2).foregroundStyle(.secondary) }
                                }.frame(maxWidth: .infinity, alignment: .leading)
                                Text(SleepPresentation.time(moment.at, seconds: true)).font(.caption).monospacedDigit().foregroundStyle(Palette.moment(moment.kind))
                            }.padding(.vertical, 13).padding(.horizontal, 8)
                                .background(moment.id == selectedMomentID ? Palette.accent.opacity(0.06) : .clear, in: RoundedRectangle(cornerRadius: 13))
                        }.buttonStyle(.plain).accessibilityElement(children: .combine).accessibilityAddTraits(moment.id == selectedMomentID ? .isSelected : [])
                    }
                }
            }
        }
    }
}
