import SwiftUI
import WakeCore

enum Palette {
    static let accent = Color("AccentColor")
    static let mint = Color("SeaColor")
    static let canvas = Color(uiColor: .systemGroupedBackground)
    static let surface = Color(uiColor: .secondarySystemGroupedBackground)
    static func phase(_ phase: NightPhase) -> Color {
        switch phase {
        case .waiting: mint
        case .ringing, .attention: .orange
        default: accent
        }
    }
    static func moment(_ kind: MomentKind) -> Color {
        switch kind {
        case .start, .end: .secondary
        case .onset, .planned: accent
        case .received, .reservation: mint
        case .alarm: .orange
        case .error: .red
        }
    }
}
struct SoftCard<Content: View>: View {
    let content: Content
    init(@ViewBuilder content: () -> Content) { self.content = content() }
    var body: some View {
        content.padding(22).frame(maxWidth: .infinity, alignment: .leading)
            .background(Palette.surface, in: RoundedRectangle(cornerRadius: 28, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 28).strokeBorder(Color.primary.opacity(0.035)))
    }
}
struct MoonHalo: View {
    let phase: NightPhase
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.scenePhase) private var scenePhase
    var body: some View {
        let color = Palette.phase(phase)
        let alive = (phase == .waiting || phase == .preparing || phase == .ringing) && scenePhase == .active && !reduceMotion
        ZStack {
            Circle().fill(RadialGradient(colors: [color.opacity(0.2), color.opacity(0.03), .clear], center: .center, startRadius: 0, endRadius: 98))
            Circle().stroke(color.opacity(0.12), lineWidth: 1).padding(14)
            Circle().stroke(color.opacity(0.09), lineWidth: 1).padding(30)
            Image(systemName: phase == .ringing ? "bell.fill" : phase == .reserved ? "moon.zzz.fill" : "moon.stars.fill")
                .font(.system(size: 60, weight: .light)).foregroundStyle(color.gradient)
                .symbolEffect(.pulse, options: .repeating, isActive: alive)
            Circle().fill(color.opacity(0.7)).frame(width: 6, height: 6).offset(x: 58, y: -62)
            Circle().fill(color.opacity(0.4)).frame(width: 4, height: 4).offset(x: -65, y: 38)
        }.frame(width: 194, height: 194).accessibilityHidden(true)
    }
}
struct MetricRow: View {
    let title: String
    let value: String
    var body: some View {
        HStack { Text(title).foregroundStyle(.secondary); Spacer(minLength: 12); Text(value).fontWeight(.medium).monospacedDigit() }
            .font(.subheadline).accessibilityElement(children: .combine)
    }
}
