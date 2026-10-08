package com.wakemeup.mobile.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import com.wakemeup.mobile.HomeState
import java.time.*
import kotlinx.coroutines.delay

@Composable fun SleepFlowPage(s: HomeState) {
    val sessions = visibleSleepSessions(s.history)
    var selectedSessionId by rememberSaveable { mutableStateOf("") }
    val selected = sessions.firstOrNull { it.sessionId == selectedSessionId }
        ?: sessions.firstOrNull { it.onsetAt != null || it.firedAt != null } ?: sessions.firstOrNull()
    Text("수면 흐름", fontSize = 30.sp, fontWeight = FontWeight.Bold)
    if (selected == null) {
        Column(Modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            NightOrb(NightPhase.READY)
            Text("아직 수면 기록이 없어요", fontWeight = FontWeight.SemiBold)
            Notice("오늘 밤 감시를 시작해 보세요.")
        }
        return
    }
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(selected.sessionId, selected.isActive) { while (selected.isActive) { now = Instant.now(); delay(30_000) } }
    var selectedMomentId by rememberSaveable(selected.sessionId) { mutableStateOf(if (selected.onsetAt != null) "onset" else "start") }
    val moments = sleepMoments(selected, s.sleepEvents, now)
    val chosen = moments.firstOrNull { it.id == selectedMomentId }
    val end = recordedEnd(selected, s.sleepEvents, now)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        sessions.forEach { session ->
            val active = session.sessionId == selected.sessionId
            Surface(shape = RoundedCornerShape(16.dp), color = if (active) Lavender.copy(alpha = .18f) else Panel,
                border = if (active) BorderStroke(1.dp, Lavender.copy(alpha = .6f)) else null,
                onClick = { selectedSessionId = session.sessionId }) {
                Text(recordDate(session.monitorStartedAt).substringBefore(" ·") + "  " + clockTime(session.monitorStartedAt), Modifier.padding(horizontal = 15.dp, vertical = 11.dp), color = if (active) Lavender else Muted, fontSize = 12.sp)
            }
        }
    }
    Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
        SleepClock(selected.monitorStartedAt, selected.onsetAt, end, moments, selectedMomentId, { selectedMomentId = it }, Modifier.fillMaxSize())
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(chosen?.title ?: "수면 흐름", color = chosen?.let { momentColor(it.kind) } ?: Lavender, fontSize = 14.sp)
            Text(clockTime(chosen?.at), fontSize = 42.sp, fontWeight = FontWeight.Light)
            Text("24시간 시계", color = Muted, fontSize = 11.sp)
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        FlowLegend("입면 대기", Muted)
        FlowLegend("입면 이후", Lavender)
        FlowLegend("예정", Lavender.copy(alpha = .45f))
    }
    Surface(shape = RoundedCornerShape(22.dp), color = Panel) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
            Detail("입면", clockTime(selected.onsetAt, seconds = true))
            Detail("폰 수신", clockTime(selected.receivedAt, seconds = true))
            Detail("수신 지연", receiptDelay(selected))
            Detail("목표", shortDuration(selected.targetMinutes))
        }
    }
    selected.failure?.let { Notice(it, Coral) }
    Text("밤사이 있었던 일", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    Column {
        moments.forEachIndexed { index, event ->
            val focused = event.id == selectedMomentId
            val color = momentColor(event.kind)
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { selectedMomentId = event.id }
                .background(if (focused) Panel else Night).padding(horizontal = 12.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Canvas(Modifier.width(20.dp).height(79.dp)) {
                    if (index != moments.lastIndex) drawLine(Muted.copy(alpha = .2f), androidx.compose.ui.geometry.Offset(size.width / 2, 28.dp.toPx()), androidx.compose.ui.geometry.Offset(size.width / 2, size.height), 1.dp.toPx())
                    drawCircle(color, 4.dp.toPx(), androidx.compose.ui.geometry.Offset(size.width / 2, 25.dp.toPx()), style = if (event.planned) Stroke(1.5.dp.toPx()) else androidx.compose.ui.graphics.drawscope.Fill)
                }
                Column(Modifier.weight(1f).padding(vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(event.title, color = if (event.planned) Muted else MaterialTheme.colorScheme.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    if (event.detail.isNotBlank()) Text(event.detail, color = Muted, fontSize = 11.sp)
                }
                Text(clockTime(event.at, seconds = true), Modifier.padding(top = 16.dp), color = color, fontSize = 12.sp)
            }
        }
    }
}

@Composable private fun FlowLegend(label: String, color: androidx.compose.ui.graphics.Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Canvas(Modifier.size(7.dp)) { drawCircle(color) }
        Text(label, color = Muted, fontSize = 11.sp)
    }
}

@Composable fun DiagnosticLogs(s: HomeState, onExport: () -> Unit, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        IconButton(onClick = onBack) { AppIcon(Glyph.BACK, Lavender) }
        Text("진단 로그", Modifier.weight(1f), fontSize = 26.sp, fontWeight = FontWeight.Bold)
    }
    OutlinedButton(onClick = onExport, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) { Text("CSV 내보내기") }
    if (s.logs.isEmpty()) Notice("아직 기록이 없어요.")
    s.logs.forEach { log ->
        var expanded by rememberSaveable(log.id) { mutableStateOf(false) }
        val title = when (log.kind) {
            "START" -> "감시 시작"; "ONSET" -> "입면 수신"; "SCHEDULED" -> "알람 예약"; "RING" -> "알람 울림"
            "CANCEL" -> "감시 종료"; "DISMISS" -> "알람 해제"; "WATCH" -> "워치 상태"; "RESTORE" -> "알람 복구"
            "IGNORED" -> "중복·이전 정보"; "VALIDATION", "FIELD_VALIDATION" -> "이전 입면 확인"; "SESSION_UPGRADED" -> "감시 전환"
            "MISSED", "MISSED_RESTORE", "BACKUP_MISSED" -> "기상시각 경과"
            "DATA_ERROR", "SCHEDULE_FAILED", "RESTORE_FAILED", "DEBUG_FAILED" -> "처리 오류"
            else -> "시스템 기록"
        }
        Surface(shape = RoundedCornerShape(18.dp), color = Panel, onClick = { expanded = !expanded }) {
            Column(Modifier.fillMaxWidth().padding(17.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Text(clockTime(Instant.ofEpochMilli(log.at), seconds = true), color = Muted, fontSize = 11.sp)
                }
                if (expanded) { Text(recordDate(Instant.ofEpochMilli(log.at)) + " · " + log.kind, color = Muted, fontSize = 11.sp); Text(log.detail, fontSize = 12.sp, lineHeight = 19.sp) }
            }
        }
    }
}
