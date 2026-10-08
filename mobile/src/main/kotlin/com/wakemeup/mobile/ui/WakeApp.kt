package com.wakemeup.mobile.ui

import android.app.TimePickerDialog
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wakemeup.core.*
import com.wakemeup.mobile.*
import java.time.*

@Composable fun WakeApp(model: HomeViewModel, onExact: () -> Unit, onNotifications: () -> Unit, onFullScreen: () -> Unit, onAlarm: () -> Unit, onExport: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    WakeScreen(state, model, onExact, onNotifications, onFullScreen, onAlarm, onExport)
}

@Composable internal fun WakeScreen(state: HomeState, model: HomeViewModel, onExact: () -> Unit, onNotifications: () -> Unit, onFullScreen: () -> Unit, onAlarm: () -> Unit, onExport: () -> Unit, initialTab: Int = 0) {
    var tab by rememberSaveable { mutableIntStateOf(initialTab) }
    var logsOpen by rememberSaveable { mutableStateOf(false) }
    val snackbars = remember { SnackbarHostState() }
    var askedNotifications by rememberSaveable { mutableStateOf(false) }
    var askedExact by rememberSaveable { mutableStateOf(false) }
    var askedFullScreen by rememberSaveable { mutableStateOf(false) }
    val step = setupStep(state)
    val setup = state.device.permissionsChecked && step != SetupStep.DONE
    LaunchedEffect(state.device.message) { state.device.message?.let { snackbars.showSnackbar(it); model.clearMessage() } }
    LaunchedEffect(state.device.permissionsChecked, step, state.settings.onboardingComplete) {
        if (!state.device.permissionsChecked || state.settings.onboardingComplete) return@LaunchedEffect
        when (step) {
            SetupStep.NOTIFICATIONS -> if (!askedNotifications) { askedNotifications = true; onNotifications() }
            SetupStep.EXACT -> if (!askedExact) { askedExact = true; onExact() }
            SetupStep.LOCK_SCREEN -> if (!askedFullScreen) { askedFullScreen = true; onFullScreen() }
            SetupStep.DONE -> model.finishSetup()
        }
    }
    BackHandler(enabled = logsOpen || tab != 0) { if (logsOpen) logsOpen = false else tab = 0 }
    Scaffold(containerColor = Night, snackbarHost = { SnackbarHost(snackbars) }, bottomBar = {
        if (!setup && !logsOpen && state.device.permissionsChecked) Surface(color = Night) {
            Column {
            if (tab == 0) TonightActions(state, model, onAlarm)
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 18.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                listOf("오늘 밤" to Glyph.MOON, "수면 흐름" to Glyph.CLOCK, "설정" to Glyph.SETTINGS).forEachIndexed { index, item ->
                    val selected = tab == index
                    val color = if (selected) Lavender else Muted
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(20.dp)).clickable { tab = index }.padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        AppIcon(item.second, color)
                        Text(item.first, color = color, fontSize = 11.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }
            }
        }
    }) { padding ->
        if (!state.device.permissionsChecked) Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Lavender) }
        else if (setup) PermissionSetup(state, model, onNotifications, onExact, onFullScreen, Modifier.padding(padding))
        else Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            when {
                logsOpen -> DiagnosticLogs(state, onExport) { logsOpen = false }
                tab == 0 -> Tonight(state, model, onAlarm) { tab = 1 }
                tab == 1 -> SleepFlowPage(state)
                else -> SettingsPage(state, model, onNotifications, onExact, onFullScreen, onExport) { logsOpen = true }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable internal fun PermissionSetup(s: HomeState, model: HomeViewModel, onNotifications: () -> Unit, onExact: () -> Unit, onFullScreen: () -> Unit, modifier: Modifier) {
    val step = setupStep(s)
    val action = when (step) { SetupStep.NOTIFICATIONS -> onNotifications; SetupStep.EXACT -> onExact; else -> onFullScreen }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Spacer(Modifier.height(16.dp))
        AppIcon(Glyph.MOON, Lavender, Modifier.size(34.dp))
        Text("알람 준비", fontSize = 32.sp, fontWeight = FontWeight.Bold)
        Text("잘 때도, 화면이 꺼져 있어도.", color = Muted, fontSize = 15.sp)
        PermissionRow("알림", "알람 소리 · 진동", s.device.notifications)
        PermissionRow("정확한 알람", "예약한 시각에 깨우기", s.device.exact)
        PermissionRow("잠금 화면", "화면에서 바로 알람 끄기", s.device.fullScreen)
        val title = when (step) { SetupStep.NOTIFICATIONS -> "알림 허용"; SetupStep.EXACT -> "정확한 알람 허용"; else -> "잠금 화면 허용" }
        Button(onClick = action, modifier = Modifier.fillMaxWidth().height(58.dp), shape = RoundedCornerShape(20.dp)) { Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp) }
        if (step == SetupStep.LOCK_SCREEN) TextButton(onClick = model::finishSetup, modifier = Modifier.fillMaxWidth()) { Text("알림으로 사용") }
        else Text("허용하면 다음 단계로 넘어갑니다.", color = Muted, fontSize = 12.sp)
    }
}

@Composable private fun PermissionRow(title: String, subtitle: String, granted: Boolean) {
    Surface(shape = RoundedCornerShape(22.dp), color = Panel) {
        Row(Modifier.fillMaxWidth().padding(19.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            AppIcon(if (granted) Glyph.CHECK else Glyph.BELL, if (granted) Mint else Muted, Modifier.size(26.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) { Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold); Text(subtitle, color = Muted, fontSize = 12.sp) }
            Text(if (granted) "완료" else "필요", color = if (granted) Mint else Dawn, fontSize = 12.sp)
        }
    }
}

@Composable internal fun Tonight(s: HomeState, model: HomeViewModel, onAlarm: () -> Unit, onFlow: () -> Unit) {
    var custom by remember { mutableStateOf(false) }
    val phase = nightPhase(s)
    val active = s.session?.isActive == true
    val session = s.session
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text("오늘 밤", fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Text(recordDate(Instant.now()), color = Muted, fontSize = 12.sp)
    }
    Surface(shape = RoundedCornerShape(24.dp), color = Panel, onClick = model::refresh) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            WatchLight(s.watchConnected && s.watch.permission && s.watch.supported)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(s.watch.name.takeIf { s.watch.nodeId.isNotBlank() } ?: "워치", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Text(when { !s.watchConnected -> "연결 대기"; !s.watch.permission -> "신체 활동 허용 필요"; !s.watch.supported -> "입면 감지 확인 필요"; else -> "연결됨" }, color = if (s.watchConnected && s.watch.permission && s.watch.supported) Mint else Dawn, fontSize = 12.sp)
            }
            AppIcon(Glyph.REFRESH, Muted, Modifier.size(18.dp))
        }
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        NightOrb(phase)
        val title = when (phase) { NightPhase.READY -> "오늘 밤 준비"; NightPhase.PREPARING -> "워치 준비 중"; NightPhase.WAITING -> "입면 대기"; NightPhase.SCHEDULED -> "기상 알람 예약됨"; NightPhase.DISCONNECTED -> "워치 연결 대기"; NightPhase.ATTENTION -> "확인 필요"; NightPhase.RINGING -> "일어날 시간" }
        val color = when (phase) { NightPhase.WAITING -> Mint; NightPhase.DISCONNECTED, NightPhase.ATTENTION, NightPhase.RINGING -> Dawn; else -> Lavender }
        Text(title, color = color, fontSize = 21.sp, fontWeight = FontWeight.Bold)
        if (phase == NightPhase.SCHEDULED || phase == NightPhase.RINGING) Text(clockTime(if (session?.firedKind == AlarmKind.BACKUP) session.backupAt else session?.alarmAt), fontSize = 56.sp, fontWeight = FontWeight.Light)
        else Text(shortDuration(if (active) session!!.targetMinutes else s.settings.targetMinutes), fontSize = 35.sp, fontWeight = FontWeight.Medium)
        Text(when (phase) { NightPhase.WAITING -> "잠든 시각부터 자동 예약"; NightPhase.PREPARING -> "감시 등록 중"; NightPhase.SCHEDULED -> "입면 ${clockTime(session?.onsetAt)}"; NightPhase.DISCONNECTED -> if (session?.backupScheduled == true) "예비 알람 유지" else "연결되면 수신 재개"; NightPhase.RINGING -> "휴대폰에서 알람이 울려요"; NightPhase.ATTENTION -> "아래 상태를 확인해 주세요"; NightPhase.READY -> "자기 전에 한 번 시작" }, color = Muted, fontSize = 13.sp)
    }
    if (!active) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            listOf(360 to "6시간", 450 to "7시간 30분", 540 to "9시간").forEach { (minutes, label) ->
                val selected = s.settings.targetMinutes == minutes
                Surface(modifier = Modifier.weight(1f), shape = RoundedCornerShape(17.dp), color = if (selected) Lavender.copy(alpha = .16f) else Panel, border = if (selected) BorderStroke(1.dp, Lavender.copy(alpha = .6f)) else null, onClick = { model.save(minutes, s.settings.backupMinutes) }) {
                    Box(Modifier.height(49.dp), contentAlignment = Alignment.Center) { Text(label, color = if (selected) Lavender else Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
                }
            }
        }
        TextButton(onClick = { custom = true }, modifier = Modifier.fillMaxWidth()) { Text("시간 직접 설정", color = Muted, fontSize = 12.sp) }
    }
    BackupControl(s, model, active)
    if (phase == NightPhase.ATTENTION) Notice(session?.failure ?: s.watch.error.ifBlank { "워치에서 신체 활동을 허용해 주세요." }, Dawn)
    if (!s.watchConnected && !active) Notice("워치에서 wakemeup을 열어 주세요.", Muted)
    if (active && session?.failure != null) TextButton(onClick = model::recover, modifier = Modifier.fillMaxWidth(), enabled = !s.device.busy) { Text("다시 확인") }
    if (session?.onsetAt != null || session?.firedAt != null) TextButton(onClick = onFlow, modifier = Modifier.fillMaxWidth()) { Text("수면 흐름 보기"); Spacer(Modifier.width(8.dp)); AppIcon(Glyph.ARROW, Lavender, Modifier.size(17.dp)) }
    if (custom) CustomDuration(s.settings.targetMinutes, { custom = false }) { model.save(it, s.settings.backupMinutes); custom = false }
}

@Composable internal fun TonightActions(s: HomeState, model: HomeViewModel, onAlarm: () -> Unit) {
    var confirmStop by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
        when {
            s.session?.status == SessionStatus.RINGING -> PrimaryAction("알람 끄기", Glyph.BELL, onAlarm)
            s.session?.isActive == true -> OutlinedButton(onClick = { confirmStop = true }, enabled = !s.device.busy, modifier = Modifier.fillMaxWidth().height(55.dp), shape = RoundedCornerShape(20.dp)) { AppIcon(Glyph.STOP, Muted, Modifier.size(16.dp)); Spacer(Modifier.width(10.dp)); Text("감시 끝내기", color = Muted) }
            else -> PrimaryAction("수면 감시 시작", Glyph.PLAY, model::start, enabled = s.canStart)
        }
    }
    if (confirmStop) AlertDialog(onDismissRequest = { confirmStop = false }, title = { Text("감시를 끝낼까요?") }, text = { Text("오늘 예약한 알람도 해제됩니다.") }, confirmButton = { TextButton(onClick = { confirmStop = false; model.cancel() }) { Text("감시 끝내기") } }, dismissButton = { TextButton(onClick = { confirmStop = false }) { Text("계속 감시") } })
}

@Composable private fun BackupControl(s: HomeState, model: HomeViewModel, active: Boolean) {
    val context = LocalContext.current
    val minutes = s.settings.backupMinutes
    fun pickTime() { if (!active) TimePickerDialog(context, { _, h, m -> model.save(s.settings.targetMinutes, h * 60 + m) }, (minutes ?: 420) / 60, (minutes ?: 420) % 60, true).show() }
    Surface(shape = RoundedCornerShape(22.dp), color = Panel) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            AppIcon(Glyph.BELL, Dawn, Modifier.size(23.dp))
            Column(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).clickable(enabled = !active) { pickTime() }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("예비 알람", fontSize = 13.sp, color = Muted)
                Text(if (active) when { s.session?.targetScheduled == true -> "기상 알람으로 전환"; s.session?.backupScheduled == true -> clockTime(s.session.backupAt); else -> "꺼짐" } else minutes?.let { "%02d:%02d".format(it / 60, it % 60) } ?: "꺼짐", fontSize = if (active && s.session?.targetScheduled == true) 14.sp else 22.sp, fontWeight = FontWeight.Medium)
            }
            if (!active) Switch(checked = minutes != null, onCheckedChange = { model.save(s.settings.targetMinutes, if (it) 420 else null) }, colors = SwitchDefaults.colors(checkedThumbColor = Night, checkedTrackColor = Dawn))
        }
    }
}

@Composable private fun SettingsPage(s: HomeState, model: HomeViewModel, onNotifications: () -> Unit, onExact: () -> Unit, onFullScreen: () -> Unit, onExport: () -> Unit, onLogs: () -> Unit) {
    Text("설정", fontSize = 30.sp, fontWeight = FontWeight.Bold)
    Text("알람", color = Muted, fontSize = 12.sp)
    SettingsItem("알림", if (s.device.notifications) "허용됨" else "허용 필요", Glyph.BELL, onNotifications)
    SettingsItem("정확한 알람", if (s.device.exact) "허용됨" else "허용 필요", Glyph.CLOCK, onExact)
    SettingsItem("잠금 화면", if (s.device.fullScreen) "허용됨" else "알림으로 사용", Glyph.MOON, onFullScreen)
    Text("기기", color = Muted, fontSize = 12.sp)
    SettingsItem(s.watch.name, if (s.watchConnected) "연결됨" else "연결 대기", Glyph.WATCH, model::refresh)
    Text("기록", color = Muted, fontSize = 12.sp)
    SettingsItem("진단 로그", "원본 기록 보기", Glyph.LOG, onLogs)
    SettingsItem("기록 내보내기", "CSV", Glyph.LOG, onExport)
    Spacer(Modifier.height(6.dp))
    Text("wakemeup  ${BuildConfig.VERSION_NAME}", color = Muted.copy(alpha = .65f), fontSize = 12.sp)
}

@Composable private fun SettingsItem(title: String, value: String, glyph: Glyph, action: () -> Unit) {
    Surface(shape = RoundedCornerShape(21.dp), color = Panel, onClick = action) {
        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            AppIcon(glyph)
            Text(title, modifier = Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(value, color = Muted, fontSize = 12.sp)
            AppIcon(Glyph.ARROW, Muted, Modifier.size(16.dp))
        }
    }
}

@Composable fun PrimaryAction(title: String, glyph: Glyph, action: () -> Unit, enabled: Boolean = true) {
    Button(onClick = action, enabled = enabled, modifier = Modifier.fillMaxWidth().height(59.dp), shape = RoundedCornerShape(21.dp), colors = ButtonDefaults.buttonColors(containerColor = Lavender, contentColor = Night)) {
        AppIcon(glyph, if (enabled) Night else Muted, Modifier.size(19.dp)); Spacer(Modifier.width(12.dp)); Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}
@Composable fun Detail(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text(label, color = Muted, fontSize = 12.sp); Text(value, fontSize = 14.sp, fontWeight = FontWeight.Medium) }
}
@Composable fun Notice(text: String, color: androidx.compose.ui.graphics.Color = Muted) { Text(text, color = color, fontSize = 13.sp, lineHeight = 20.sp) }

@Composable private fun CustomDuration(initial: Int, onDismiss: () -> Unit, onSave: (Int) -> Unit) {
    var hours by remember { mutableStateOf((initial / 60).toString()) }
    var minutes by remember { mutableStateOf((initial % 60).toString()) }
    val h = hours.toLongOrNull(); val m = minutes.toLongOrNull()
    val total = if (h != null && m != null && h in 0..(Int.MAX_VALUE / 60).toLong() && m in 0..59) h * 60 + m else null
    val valid = total != null && total in 360..Int.MAX_VALUE.toLong()
    AlertDialog(onDismissRequest = onDismiss, title = { Text("목표 수면 시간") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(hours, { hours = it }, label = { Text("시간") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f), singleLine = true)
                OutlinedTextField(minutes, { minutes = it }, label = { Text("분") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f), singleLine = true)
            }
            Text("6시간 이상", color = if (valid) Muted else Coral, fontSize = 12.sp)
        }
    }, confirmButton = { TextButton(onClick = { onSave(total!!.toInt()) }, enabled = valid) { Text("저장") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } })
}
