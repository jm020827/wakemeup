import SwiftUI
import WakeCore

struct SleepClock: View {
    let session: NightSession
    let moments: [SleepMoment]
    let now: Date
    let selectedID: String
    let select: (String) -> Void
    private var selected: SleepMoment? { moments.first { $0.id == selectedID } ?? moments.first }
    var body: some View {
        ZStack {
            Canvas { context, size in
                let center = CGPoint(x: size.width / 2, y: size.height / 2)
                let radius = size.width * 0.36
                let width: CGFloat = 14
                context.stroke(Path(ellipseIn: CGRect(x: center.x - radius, y: center.y - radius, width: radius * 2, height: radius * 2)), with: .color(Color.primary.opacity(0.05)), lineWidth: width)
                let end = SleepPresentation.end(session, now: now)
                arc(from: session.startedAt, to: session.onsetAt ?? end, center: center, radius: radius, color: .secondary.opacity(0.45), context: &context)
                if let onset = session.onsetAt { arc(from: onset, to: end, center: center, radius: radius, color: Palette.accent, context: &context) }
                for hour in 0..<24 {
                    let angle = Double(hour) * .pi / 12 - .pi / 2
                    let start = point(angle: angle, center: center, radius: radius + 17)
                    let finish = point(angle: angle, center: center, radius: radius + (hour % 6 == 0 ? 24 : 21))
                    var tick = Path(); tick.move(to: start); tick.addLine(to: finish)
                    context.stroke(tick, with: .color(.secondary.opacity(hour % 6 == 0 ? 0.6 : 0.2)), lineWidth: 1)
                    if hour % 6 == 0 {
                        context.draw(Text(String(format: "%02d", hour)).font(.caption2).foregroundStyle(.secondary), at: point(angle: angle, center: center, radius: radius + 39))
                    }
                }
                for moment in moments {
                    let location = clockPoint(moment.at, center: center, radius: radius)
                    let color = Palette.moment(moment.kind)
                    if moment.id == selected?.id { context.fill(Path(ellipseIn: CGRect(x: location.x - 13, y: location.y - 13, width: 26, height: 26)), with: .color(color.opacity(0.16))) }
                    context.fill(Path(ellipseIn: CGRect(x: location.x - 7, y: location.y - 7, width: 14, height: 14)), with: .color(Palette.surface))
                    let dot = Path(ellipseIn: CGRect(x: location.x - 4.5, y: location.y - 4.5, width: 9, height: 9))
                    if moment.planned { context.stroke(dot, with: .color(color.opacity(0.65)), lineWidth: 1.6) }
                    else { context.fill(dot, with: .color(color)) }
                }
            }
            .accessibilityLabel("24시간 수면 시계")
            .accessibilityValue(moments.map { "\($0.title) \(SleepPresentation.time($0.at))" }.joined(separator: ", "))
            VStack(spacing: 9) {
                Text(selected?.title ?? "수면 흐름").font(.subheadline.weight(.medium)).foregroundStyle(Palette.moment(selected?.kind ?? .onset))
                Text(SleepPresentation.time(selected?.at)).font(.system(size: 43, weight: .light, design: .rounded)).monospacedDigit().contentTransition(.numericText())
                Text("24시간 시계").font(.caption2).foregroundStyle(.secondary)
            }.allowsHitTesting(false).accessibilityHidden(true)
        }
        .aspectRatio(1, contentMode: .fit)
        .overlay {
            GeometryReader { geometry in
                Color.clear.contentShape(Rectangle()).gesture(SpatialTapGesture().onEnded { value in
                    let center = CGPoint(x: geometry.size.width / 2, y: geometry.size.height / 2)
                    let radius = geometry.size.width * 0.36
                    let nearest = moments.min { distance(value.location, clockPoint($0.at, center: center, radius: radius)) < distance(value.location, clockPoint($1.at, center: center, radius: radius)) }
                    if let nearest, distance(value.location, clockPoint(nearest.at, center: center, radius: radius)) < 32 { select(nearest.id) }
                })
            }.accessibilityHidden(true)
        }
    }
    private func arc(from: Date, to: Date, center: CGPoint, radius: CGFloat, color: Color, context: inout GraphicsContext) {
        guard to > from else { return }
        let calendar = Calendar.current
        let days = calendar.dateComponents([.day], from: calendar.startOfDay(for: from), to: calendar.startOfDay(for: to)).day ?? 0
        let start = SleepPresentation.faceFraction(from)
        let span = min(0.9999, max(0, Double(days) + SleepPresentation.faceFraction(to) - start))
        guard span > 0 else { return }
        var path = Path()
        path.addArc(center: center, radius: radius, startAngle: .degrees(start * 360 - 90), endAngle: .degrees((start + span) * 360 - 90), clockwise: false)
        context.stroke(path, with: .color(color), style: StrokeStyle(lineWidth: 14, lineCap: .round))
    }
    private func point(angle: Double, center: CGPoint, radius: CGFloat) -> CGPoint { CGPoint(x: center.x + CGFloat(cos(angle)) * radius, y: center.y + CGFloat(sin(angle)) * radius) }
    private func clockPoint(_ date: Date, center: CGPoint, radius: CGFloat) -> CGPoint { point(angle: SleepPresentation.faceFraction(date) * 2 * .pi - .pi / 2, center: center, radius: radius) }
    private func distance(_ a: CGPoint, _ b: CGPoint) -> CGFloat { hypot(a.x - b.x, a.y - b.y) }
}
