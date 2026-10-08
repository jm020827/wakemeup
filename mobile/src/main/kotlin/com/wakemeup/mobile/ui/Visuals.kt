package com.wakemeup.mobile.ui

import android.graphics.Paint
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.*
import java.time.*
import kotlin.math.*

enum class Glyph { MOON, WATCH, CLOCK, SETTINGS, BELL, PLAY, STOP, ARROW, BACK, CHECK, LOG, REFRESH }

@Composable fun AppIcon(glyph: Glyph, color: Color = Muted, modifier: Modifier = Modifier.size(22.dp)) {
    Canvas(modifier) {
        withTransform({ scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) }) {
            val stroke = Stroke(1.7f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            fun line(x: Float, y: Float, x2: Float, y2: Float) = drawLine(color, Offset(x, y), Offset(x2, y2), 1.7f, StrokeCap.Round)
            when (glyph) {
                Glyph.MOON -> drawPath(Path().apply { moveTo(17f, 3f); cubicTo(3f, 1f, 0f, 20f, 15f, 21f); cubicTo(18f, 21f, 21f, 19f, 22f, 16f); cubicTo(11f, 20f, 6f, 9f, 17f, 3f); close() }, color, style = stroke)
                Glyph.WATCH -> { drawRoundRect(color, Offset(5f, 6f), Size(14f, 12f), CornerRadius(4f), style = stroke); line(8f, 5f, 9f, 1f); line(16f, 5f, 15f, 1f); line(8f, 19f, 9f, 23f); line(16f, 19f, 15f, 23f); line(12f, 9f, 12f, 12f); line(12f, 12f, 15f, 13f) }
                Glyph.CLOCK -> { drawCircle(color, 9f, Offset(12f, 12f), style = stroke); line(12f, 7f, 12f, 12f); line(12f, 12f, 16f, 14f) }
                Glyph.SETTINGS -> { drawCircle(color, 4f, Offset(12f, 12f), style = stroke); drawCircle(color, 8f, Offset(12f, 12f), style = stroke); repeat(8) { val a = it * PI / 4; line(12 + cos(a).toFloat() * 8, 12 + sin(a).toFloat() * 8, 12 + cos(a).toFloat() * 10.5f, 12 + sin(a).toFloat() * 10.5f) } }
                Glyph.BELL -> { drawPath(Path().apply { moveTo(5f, 17f); lineTo(7f, 14f); lineTo(7f, 9f); cubicTo(7f, 2f, 17f, 2f, 17f, 9f); lineTo(17f, 14f); lineTo(19f, 17f); close() }, color, style = stroke); drawArc(color, 0f, 180f, false, Offset(9f, 17f), Size(6f, 5f), style = stroke) }
                Glyph.PLAY -> drawPath(Path().apply { moveTo(8f, 4f); lineTo(20f, 12f); lineTo(8f, 20f); close() }, color)
                Glyph.STOP -> drawRoundRect(color, Offset(6f, 6f), Size(12f, 12f), CornerRadius(3f))
                Glyph.ARROW -> { line(9f, 6f, 15f, 12f); line(15f, 12f, 9f, 18f) }
                Glyph.BACK -> { line(15f, 6f, 9f, 12f); line(9f, 12f, 15f, 18f) }
                Glyph.CHECK -> { line(5f, 12f, 10f, 17f); line(10f, 17f, 20f, 6f) }
                Glyph.LOG -> { drawRoundRect(color, Offset(5f, 3f), Size(14f, 18f), CornerRadius(3f), style = stroke); line(8f, 8f, 16f, 8f); line(8f, 12f, 16f, 12f); line(8f, 16f, 13f, 16f) }
                Glyph.REFRESH -> { drawArc(color, 40f, 290f, false, Offset(4f, 4f), Size(16f, 16f), style = stroke); line(19f, 3f, 20f, 8f); line(20f, 8f, 15f, 7f) }
            }
        }
    }
}

@Composable fun WatchLight(connected: Boolean, modifier: Modifier = Modifier) {
    val color = if (connected) Mint else Muted
    Box(modifier.size(48.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            if (connected) drawCircle(Brush.radialGradient(listOf(Mint.copy(alpha = .22f), Color.Transparent)), size.minDimension / 2)
        }
        AppIcon(Glyph.WATCH, color, Modifier.size(25.dp))
    }
}

@Composable fun NightOrb(phase: NightPhase, modifier: Modifier = Modifier) {
    val color = when (phase) { NightPhase.WAITING -> Mint; NightPhase.SCHEDULED -> Lavender; NightPhase.RINGING -> Dawn; NightPhase.ATTENTION, NightPhase.DISCONNECTED -> Dawn; else -> Lavender }
    val alive = phase in setOf(NightPhase.WAITING, NightPhase.PREPARING, NightPhase.RINGING)
    val breathing = if (alive) {
        val transition = rememberInfiniteTransition(label = "night")
        val value by transition.animateFloat(.78f, 1f, infiniteRepeatable(tween(2200), RepeatMode.Reverse), label = "breathing")
        value
    } else 1f
    Box(modifier.size(160.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val radius = size.minDimension / 2
            drawCircle(Brush.radialGradient(listOf(color.copy(alpha = .19f), color.copy(alpha = .02f), Color.Transparent)), radius)
            drawCircle(color.copy(alpha = .10f), radius * if (alive) breathing else .9f, style = Stroke(1.dp.toPx()))
            drawCircle(color.copy(alpha = .12f), radius * .71f, style = Stroke(1.dp.toPx()))
            drawCircle(color.copy(alpha = .75f), 2.5.dp.toPx(), center + Offset(radius * .50f, -radius * .54f))
            drawCircle(color.copy(alpha = .40f), 1.5.dp.toPx(), center + Offset(-radius * .64f, radius * .28f))
        }
        AppIcon(if (phase == NightPhase.RINGING) Glyph.BELL else Glyph.MOON, color, Modifier.size(58.dp))
        if (phase == NightPhase.SCHEDULED) Text("z z", modifier = Modifier.offset(x = 40.dp, y = (-31).dp), color = Lavender.copy(alpha = .75f), fontSize = 16.sp)
    }
}

fun momentColor(kind: MomentKind): Color = when (kind) {
    MomentKind.START -> Muted
    MomentKind.ONSET -> Lavender
    MomentKind.RECEIVED, MomentKind.RESERVED -> Mint
    MomentKind.ALARM -> Dawn
    MomentKind.ERROR -> Coral
    MomentKind.END -> Muted
    MomentKind.PLANNED -> Lavender.copy(alpha = .45f)
}

private fun faceFraction(at: Instant, zone: ZoneId): Float {
    val time = at.atZone(zone).toLocalTime()
    return (time.toSecondOfDay() + time.nano / 1_000_000_000f) / 86400f
}
private fun clockPoint(at: Instant, zone: ZoneId, center: Offset, radius: Float): Offset {
    val angle = (faceFraction(at, zone) * 2 * PI - PI / 2).toFloat()
    return center + Offset(cos(angle) * radius, sin(angle) * radius)
}

@Composable fun SleepClock(started: Instant, onset: Instant?, end: Instant, moments: List<SleepMoment>, selectedId: String?, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    val zone = ZoneId.systemDefault()
    Canvas(modifier.aspectRatio(1f).semantics { contentDescription = "24시간 수면 시계. " + moments.joinToString { "${it.title} ${clockTime(it.at, zone)}" } }
        .pointerInput(moments, zone) {
            detectTapGestures { position ->
                val center = Offset(size.width / 2f, size.height / 2f)
                val radius = size.width * .36f
                val closest = moments.minByOrNull { (position - clockPoint(it.at, zone, center, radius)).getDistance() }
                if (closest != null && (position - clockPoint(closest.at, zone, center, radius)).getDistance() < 32.dp.toPx()) onSelect(closest.id)
            }
        }) {
        val radius = size.width * .36f
        val thickness = 13.dp.toPx()
        val arcBox = Offset(center.x - radius, center.y - radius)
        val arcSize = Size(radius * 2, radius * 2)
        drawCircle(Panel, radius, style = Stroke(thickness))
        fun segment(from: Instant, to: Instant, color: Color) {
            val sweep = (Duration.between(from, to).toMillis().coerceAtLeast(0) / 86_400_000f * 360).coerceAtMost(359.9f)
            if (sweep > 0) drawArc(color, faceFraction(from, zone) * 360 - 90, sweep, false, arcBox, arcSize, style = Stroke(thickness, cap = StrokeCap.Round))
        }
        segment(started, onset ?: end, Muted.copy(alpha = .45f))
        if (onset != null) segment(onset, end, Lavender)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Muted.toArgb(); textAlign = Paint.Align.CENTER; textSize = 11.sp.toPx() }
        repeat(24) { hour ->
            val a = hour * PI / 12 - PI / 2
            val p = Offset(cos(a).toFloat(), sin(a).toFloat())
            val r = radius + thickness / 2 + 8.dp.toPx()
            drawLine(Muted.copy(alpha = if (hour % 6 == 0) .8f else .25f), center + p * r, center + p * (r + if (hour % 6 == 0) 7.dp.toPx() else 3.dp.toPx()), 1.dp.toPx())
            if (hour % 6 == 0) drawIntoCanvas { canvas ->
                val point = center + p * (radius + 32.dp.toPx())
                canvas.nativeCanvas.drawText("%02d".format(hour), point.x, point.y - (paint.ascent() + paint.descent()) / 2, paint)
            }
        }
        moments.forEach { event ->
            val point = clockPoint(event.at, zone, center, radius)
            if (event.id == selectedId) drawCircle(momentColor(event.kind).copy(alpha = .2f), 13.dp.toPx(), point)
            drawCircle(Night, 6.5.dp.toPx(), point)
            drawCircle(momentColor(event.kind), 4.dp.toPx(), point, style = if (event.planned) Stroke(1.5.dp.toPx()) else Fill)
        }
    }
}
