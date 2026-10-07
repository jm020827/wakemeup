package com.wakemeup.mobile.ui

import android.app.TimePickerDialog
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wakemeup.core.*
import com.wakemeup.mobile.*
import java.time.*
import java.time.format.DateTimeFormatter

private fun localTime(at: Instant?): String = at?.atZone(ZoneId.systemDefault())?.format(DateTimeFormatter.ofPattern("M/d HH:mm")) ?: "—"
private fun duration(minutes: Int) = "${minutes / 60}시간" + if (minutes % 60 == 0) "" else " ${minutes % 60}분"
private fun status(s: SleepSession?): String = when (s?.status) {
    SessionStatus.MONITORING -> if (s.watchMonitoring) "입면 정보를 기다리고 있어요" else "워치 감시 등록을 기다리고 있어요"
    SessionStatus.OBSERVED -> "수면 이벤트 수신을 확인했어요"
    SessionStatus.SCHEDULING -> "기상 알람을 예약하고 있어요"
    SessionStatus.SCHEDULED -> "기상 알람이 예약되었어요"
    SessionStatus.FAILED -> "감시·예약 상태 확인이 필요해요"
    SessionStatus.RINGING -> "기상 알람이 울리고 있어요"
    SessionStatus.COMPLETED -> "오늘의 알람을 해제했어요"
    SessionStatus.CANCELLED -> "수면 감시를 취소했어요"
    else -> "오늘 밤의 수면을 준비해요"
}

@Composable fun WakeApp(model: HomeViewModel, onExact: () -> Unit, onNotifications: () -> Unit, onFullScreen: () -> Unit, onAlarm: () -> Unit, onExport: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val snackbars = remember { SnackbarHostState() }
    LaunchedEffect(state.device.message) { state.device.message?.let { snackbars.showSnackbar(it); model.clearMessage() } }
    Scaffold(containerColor = Night, snackbarHost = { SnackbarHost(snackbars) }, bottomBar = {
        Surface(color = Night, shadowElevation = 8.dp) {
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                listOf("오늘 밤", "수면 기록", "설정·검증").forEachIndexed { index, title ->
                    TextButton(onClick = { tab = index }, colors = ButtonDefaults.textButtonColors(contentColor = if (index == tab) Lavender else Muted)) { Text(title, fontWeight = if (index == tab) FontWeight.Bold else FontWeight.Normal) }
                }
            }
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column { Text("wakemeup", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, letterSpacing = 1.sp); Text("잠든 순간부터 시작하는 알람", color = Muted, style = MaterialTheme.typography.labelMedium) }
                Text("☾", color = Dawn, fontSize = 34.sp)
            }
            when (tab) {
                0 -> Tonight(state, model, onExact, onNotifications, onAlarm)
                1 -> Logs(state, onExport)
                else -> SettingsAndValidation(state, model, onExact, onNotifications, onFullScreen, onExport)
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable private fun Tonight(s: HomeState, model: HomeViewModel, onExact: () -> Unit, onNotifications: () -> Unit, onAlarm: () -> Unit) {
    var custom by remember { mutableStateOf(false) }
    var validation by rememberSaveable { mutableStateOf(true) }
    val active = s.session?.isActive == true
    LaunchedEffect(s.verified) { if (!s.verified) validation = true }
    Text("잠든 순간부터,\n나만의 기상 시간.", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, lineHeight = 40.sp)
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Brush.linearGradient(listOf(Color(0xFF292C51), Panel))).padding(24.dp)) {
        Moon(Modifier.align(Alignment.TopEnd).size(106.dp))
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(if (active) "진행 중인 수면 세션" else "목표 수면 시간", color = Lavender, style = MaterialTheme.typography.labelLarge)
            Text(duration(if (active) s.session!!.targetMinutes else s.settings.targetMinutes), fontSize = 34.sp, fontWeight = FontWeight.Medium)
            Text(if (active && (!s.device.exact || !s.device.notifications)) "필수 알람 권한 확인이 필요해요" else status(s.session), color = Muted)
            if (active) {
                HorizontalDivider(color = Muted.copy(alpha = .2f))
                Detail("추정 입면", localTime(s.session?.onsetAt)); Detail("휴대폰 수신", localTime(s.session?.receivedAt))
                Detail(if (s.session?.validationOnly == true) "계산된 기상 · 검증용" else "목표 기상 알람", localTime(s.session?.alarmAt), Dawn)
                Detail("예비 알람", if (s.session?.backupScheduled == true) localTime(s.session?.backupAt) else if (s.session?.targetScheduled == true) "목표 알람으로 교체됨" else "예약 없음")
            } else Text("감시 시작을 누른 시각과 입면시각은 달라요.\n워치가 알려준 입면시각부터 시간을 셉니다.", style = MaterialTheme.typography.bodySmall, color = Muted)
        }
    }
    if (!active) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(360, 450, 540).forEach { minutes -> FilterChip(selected = s.settings.targetMinutes == minutes, onClick = { model.save(minutes, s.settings.backupMinutes) }, label = { Text(duration(minutes), fontSize = 12.sp) }, modifier = Modifier.weight(1f)) }
        }
        TextButton(onClick = { custom = true }, modifier = Modifier.fillMaxWidth()) { Text("직접 입력 · 6시간 이상") }
        Backup(s, model)
        if (s.verified) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("수신 검증 모드"); Text("검증 모드에서는 예비 알람만 울립니다.", color = Muted, fontSize = 12.sp) }; Switch(checked = validation, onCheckedChange = { validation = it }) }
        else Note("먼저 실기기에서 수신을 검증해요", "이번 세션은 원래 입면시각과 수신 지연을 기록합니다. 자동 기상 알람은 검증을 완료한 뒤 켤 수 있어요.")
    }
    s.session?.failure?.let { Note("확인이 필요해요", it) }
    WatchCard(s, model::refresh)
    if (!s.device.exact || !s.device.notifications) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("알람을 위한 권한", fontWeight = FontWeight.Bold)
            if (!s.device.exact) OutlinedButton(onClick = onExact, modifier = Modifier.fillMaxWidth()) { Text("정확한 알람 접근 허용") }
            if (!s.device.notifications) OutlinedButton(onClick = onNotifications, modifier = Modifier.fillMaxWidth()) { Text("알림 허용") }
        }
    }
    when {
        s.session?.status == SessionStatus.RINGING -> Button(onClick = onAlarm, modifier = Modifier.fillMaxWidth().height(60.dp)) { Text("울리는 알람 해제하기") }
        active -> {
            if (s.session?.failure != null) OutlinedButton(onClick = model::recover, modifier = Modifier.fillMaxWidth()) { Text("권한 확인 후 복구 시도") }
            OutlinedButton(onClick = model::cancel, enabled = !s.device.busy, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text(if (s.session?.validationOnly == true) "검증 감시 종료" else "감시 취소 · 알람 모두 해제") }
        }
        else -> Button(onClick = { model.start(validation || !s.verified) }, enabled = s.canStart, modifier = Modifier.fillMaxWidth().height(60.dp), shape = RoundedCornerShape(18.dp)) { Text(if (validation || !s.verified) "수신 검증 시작" else "수면 감시 시작", fontSize = 17.sp, fontWeight = FontWeight.Bold) }
    }
    if (custom) CustomDuration(s.settings.targetMinutes, onDismiss = { custom = false }, onSave = { model.save(it, s.settings.backupMinutes); custom = false })
}

@Composable private fun Backup(s: HomeState, model: HomeViewModel) {
    val context = LocalContext.current
    Surface(shape = RoundedCornerShape(20.dp), color = Panel) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("예비 알람", fontWeight = FontWeight.Medium); Text("입면 감지 실패에 대비한 고정 시각", fontSize = 12.sp, color = Muted) }; Switch(checked = s.settings.backupMinutes != null, onCheckedChange = { model.save(s.settings.targetMinutes, if (it) 420 else null) }) }
            s.settings.backupMinutes?.let { minutes -> TextButton(onClick = { TimePickerDialog(context, { _, h, m -> model.save(s.settings.targetMinutes, h * 60 + m) }, minutes / 60, minutes % 60, true).show() }) { Text("매 세션 다음 ${"%02d:%02d".format(minutes / 60, minutes % 60)}", color = Dawn, fontSize = 20.sp) } }
        }
    }
}
@Composable private fun WatchCard(s: HomeState, refresh: () -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = Panel) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(s.watch.name, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f)); Text(if (s.watchConnected) "연결됨" else "연결 대기", color = if (s.watchConnected) Color(0xFF9DD9BC) else Dawn, fontSize = 12.sp) }
        Text(when { !s.watch.supported -> s.watch.error.ifBlank { "수면 상태 지원 확인이 필요합니다." }; !s.watch.permission -> "워치에서 신체 활동 권한을 허용해 주세요."; !s.watchConnected -> "연결이 복구되면 저장된 수면 정보가 동기화됩니다."; s.session?.watchMonitoring == true -> "백그라운드 수면 감시가 등록되어 있어요."; else -> "수면 상태 지원 확인됨 · 감시 시작 가능" }, fontSize = 13.sp, color = Muted)
        TextButton(onClick = refresh, contentPadding = PaddingValues(0.dp)) { Text("워치 상태 새로고침") }
    } }
}
@Composable private fun Logs(s: HomeState, onExport: () -> Unit) {
    Text("수면 기록", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
    Text("원래 입면시각, 수신 지연, 알람 실행 오차를 기록합니다. 내보내기 파일의 시각은 UTC입니다.", color = Muted)
    OutlinedButton(onClick = onExport, modifier = Modifier.fillMaxWidth()) { Text("전체 로그 CSV 내보내기") }
    if (s.logs.isEmpty()) Note("아직 기록이 없어요", "워치 연결 후 첫 수신 검증을 시작해 보세요.")
    s.logs.forEach { log -> Surface(shape = RoundedCornerShape(16.dp), color = Panel) { Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(log.kind, color = Lavender, style = MaterialTheme.typography.labelLarge); Text(localTime(Instant.ofEpochMilli(log.at)), color = Muted, style = MaterialTheme.typography.labelSmall) }
        Text(log.detail, fontSize = 13.sp)
    } } }
}
@Composable private fun SettingsAndValidation(s: HomeState, model: HomeViewModel, onExact: () -> Unit, onNotifications: () -> Unit, onFullScreen: () -> Unit, onExport: () -> Unit) {
    var notes by rememberSaveable { mutableStateOf("") }
    var confirmed by rememberSaveable { mutableStateOf(false) }
    Text("설정과 실기기 검증", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
    Text("${s.watch.name}\n${if (s.verified) "이 워치의 수신 검증 완료" else "자동 알람을 위한 실제 취침 테스트 필요"}", color = Lavender)
    Surface(shape = RoundedCornerShape(20.dp), color = Panel) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("알람 권한", fontWeight = FontWeight.Bold)
        Detail("정확한 알람", if (s.device.exact) "허용됨" else "필요")
        Detail("알림", if (s.device.notifications) "허용됨" else "필요")
        Detail("잠금 화면 전체 화면", if (s.device.fullScreen) "허용됨" else "설정 필요 · 알림으로 해제 가능")
        OutlinedButton(onClick = onExact, modifier = Modifier.fillMaxWidth()) { Text("정확한 알람 설정") }
        OutlinedButton(onClick = onNotifications, modifier = Modifier.fillMaxWidth()) { Text("알림 설정") }
        OutlinedButton(onClick = onFullScreen, modifier = Modifier.fillMaxWidth()) { Text("잠금 화면 전체 화면 알람 설정") }
    } }
    Note("첫 취침 테스트", "워치를 착용하고 수신 검증을 시작하세요. 기상 전에 원래 입면시각이 도착하는지 확인하고, 예비 알람의 소리·진동을 잠금·절전·수면모드에서 확인하세요.")
    s.session?.let { Detail("추정 입면", localTime(it.onsetAt)); Detail("수신 시각", localTime(it.receivedAt)); if (it.onsetAt != null && it.receivedAt != null) Detail("수신 지연", "${Duration.between(it.onsetAt, it.receivedAt).toMinutes()}분") }
    OutlinedTextField(value = notes, onValueChange = { notes = it }, modifier = Modifier.fillMaxWidth(), minLines = 4, label = { Text("실제 취침 테스트 기록") }, placeholder = { Text("실제 입면 00:20 / 추정 차이 +10분\n기상 전 수신 여부, 잠금·절전·수면모드 소리·진동\n배터리 90% → 82%, 기기·OS 버전") })
    Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(checked = confirmed, onCheckedChange = { confirmed = it }); Text("원래 입면시각이 기상 전에 수신되고 소리·진동이 정상 동작함을 실기기에서 확인했어요.", fontSize = 13.sp) }
    Button(onClick = { model.verify(notes, confirmed) }, enabled = confirmed && notes.isNotBlank() && s.session?.validationOnly == true && s.session.onsetAt != null && !s.device.busy, modifier = Modifier.fillMaxWidth()) { Text("검증 결과 저장 · 자동 알람 활성화") }
    OutlinedButton(onClick = { model.record(notes) }, enabled = notes.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("테스트 메모만 저장") }
    OutlinedButton(onClick = onExport, modifier = Modifier.fillMaxWidth()) { Text("검증 로그 CSV 내보내기") }
    Text("서버·계정 없이 이 기기에 저장합니다. 목표 시간은 입면 후 경과시간이며 중간 각성 시간을 빼지 않습니다.", color = Muted, fontSize = 12.sp)
}
@Composable private fun Detail(label: String, value: String, color: Color = MaterialTheme.colorScheme.onSurface) { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(label, color = Muted, fontSize = 13.sp, modifier = Modifier.weight(1f)); Text(value, color = color, fontSize = 13.sp) } }
@Composable private fun Note(title: String, body: String) { Surface(shape = RoundedCornerShape(18.dp), color = Color(0xFF242941)) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(title, color = Dawn, fontWeight = FontWeight.Medium); Text(body, fontSize = 13.sp, color = Muted) } } }
@Composable private fun Moon(modifier: Modifier) { Canvas(modifier) {
    drawCircle(Lavender.copy(alpha = .09f), size.minDimension / 2)
    drawCircle(Lavender.copy(alpha = .8f), size.minDimension * .25f, Offset(size.width * .48f, size.height * .46f))
    drawCircle(Color(0xFF292C51), size.minDimension * .23f, Offset(size.width * .60f, size.height * .36f))
    drawCircle(Dawn, 3.dp.toPx(), Offset(size.width * .78f, size.height * .70f))
    drawCircle(Lavender.copy(alpha = .5f), 2.dp.toPx(), Offset(size.width * .2f, size.height * .18f))
} }
@Composable private fun CustomDuration(initial: Int, onDismiss: () -> Unit, onSave: (Int) -> Unit) {
    var hours by remember { mutableStateOf((initial / 60).toString()) }; var minutes by remember { mutableStateOf((initial % 60).toString()) }
    val h = hours.toLongOrNull(); val m = minutes.toLongOrNull(); val total = if (h != null && m != null && h in 0..(Int.MAX_VALUE / 60).toLong() && m in 0..59) h * 60 + m else null
    val valid = total != null && total in 360..Int.MAX_VALUE.toLong()
    AlertDialog(onDismissRequest = onDismiss, title = { Text("목표 시간 직접 입력") }, text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedTextField(hours, { hours = it }, label = { Text("시간") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f), singleLine = true); OutlinedTextField(minutes, { minutes = it }, label = { Text("분") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f), singleLine = true) }
        Text("6시간 이상, 분은 0~59로 입력해 주세요.", color = if (valid) Muted else MaterialTheme.colorScheme.error)
    } }, confirmButton = { TextButton(onClick = { onSave(total!!.toInt()) }, enabled = valid) { Text("저장") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } })
}
