package com.wakemeup.mobile.ui

import com.wakemeup.core.*
import com.wakemeup.mobile.HomeState
import com.wakemeup.mobile.data.LogEntity
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class NightPhase { READY, PREPARING, WAITING, SCHEDULED, DISCONNECTED, ATTENTION, RINGING }
enum class SetupStep { NOTIFICATIONS, EXACT, LOCK_SCREEN, DONE }
enum class MomentKind { START, ONSET, RECEIVED, RESERVED, ALARM, END, ERROR, PLANNED }
data class SleepMoment(val id: String, val kind: MomentKind, val title: String, val at: Instant, val detail: String = "", val planned: Boolean = false)

fun setupStep(s: HomeState): SetupStep = when {
    !s.device.notifications -> SetupStep.NOTIFICATIONS
    !s.device.exact -> SetupStep.EXACT
    !s.settings.onboardingComplete && !s.device.fullScreen -> SetupStep.LOCK_SCREEN
    else -> SetupStep.DONE
}

fun nightPhase(s: HomeState): NightPhase {
    val session = s.session
    return when {
        session?.status == SessionStatus.RINGING -> NightPhase.RINGING
        session?.isActive != true -> NightPhase.READY
        !s.device.exact || !s.device.notifications || session.failure != null -> NightPhase.ATTENTION
        session.targetScheduled -> NightPhase.SCHEDULED
        !s.watchConnected -> NightPhase.DISCONNECTED
        !s.watch.supported || !s.watch.permission -> NightPhase.ATTENTION
        !session.watchMonitoring || session.validationOnly || session.status == SessionStatus.SCHEDULING -> NightPhase.PREPARING
        else -> NightPhase.WAITING
    }
}

fun clockTime(at: Instant?, zone: ZoneId = ZoneId.systemDefault(), seconds: Boolean = false): String =
    at?.atZone(zone)?.format(DateTimeFormatter.ofPattern(if (seconds) "HH:mm:ss" else "HH:mm")) ?: "—"
fun recordDate(at: Instant, zone: ZoneId = ZoneId.systemDefault()): String =
    at.atZone(zone).format(DateTimeFormatter.ofPattern("M월 d일 · E요일", Locale.KOREAN))
fun shortDuration(minutes: Int): String = "${minutes / 60}시간" + if (minutes % 60 == 0) "" else " ${minutes % 60}분"
fun receiptDelay(s: SleepSession): String {
    val onset = s.onsetAt ?: return "—"
    val received = s.receivedAt ?: return "—"
    val seconds = Duration.between(onset, received).seconds
    if (seconds < 0) return "시각 확인 필요"
    val hours = seconds / 3600
    val minutes = seconds % 3600 / 60
    return listOfNotNull(if (hours > 0) "${hours}시간" else null, if (minutes > 0) "${minutes}분" else null, "${seconds % 60}초").joinToString(" ")
}

fun visibleSleepSessions(history: List<SleepSession>): List<SleepSession> = history.filter {
    it.isActive || it.onsetAt != null || it.firedAt != null || it.failure != null
}

fun sleepMoments(s: SleepSession, logs: List<LogEntity>, now: Instant, zone: ZoneId = ZoneId.systemDefault()): List<SleepMoment> {
    val result = mutableListOf(SleepMoment("start", MomentKind.START, "감시 시작", s.monitorStartedAt))
    s.onsetAt?.let { result += SleepMoment("onset", MomentKind.ONSET, "입면", it, "워치가 추정한 잠든 시각") }
    s.receivedAt?.let { result += SleepMoment("received", MomentKind.RECEIVED, "폰 수신", it, "입면 후 ${receiptDelay(s)}") }
    val sessionLogs = logs.filter { it.sessionId == s.sessionId }.sortedBy { it.at }
    sessionLogs.firstOrNull { it.kind == "SCHEDULED" }?.let {
        result += SleepMoment("reserved", MomentKind.RESERVED, "알람 예약", Instant.ofEpochMilli(it.at), "기상 ${clockTime(s.alarmAt, zone)}")
    }
    s.firedAt?.let { result += SleepMoment("alarm", MomentKind.ALARM, if (s.firedKind == AlarmKind.BACKUP) "예비 알람 울림" else "기상 알람 울림", it) }
    sessionLogs.lastOrNull { it.kind == "DISMISS" || it.kind == "CANCEL" }?.let {
        result += SleepMoment("end", MomentKind.END, if (it.kind == "DISMISS") "알람 해제" else "감시 종료", Instant.ofEpochMilli(it.at))
    }
    s.failure?.let { failure ->
        sessionLogs.lastOrNull { it.kind in setOf("MISSED", "MISSED_RESTORE", "SCHEDULE_FAILED", "RESTORE_FAILED", "BACKUP_MISSED") }?.let {
            result += SleepMoment("error", MomentKind.ERROR, "알람 확인 필요", Instant.ofEpochMilli(it.at), failure)
        }
    }
    s.alarmAt?.takeIf { s.firedKind != AlarmKind.TARGET }?.let {
        result += SleepMoment("planned", MomentKind.PLANNED, if (s.targetScheduled) "기상 예정" else "계산된 기상", it,
            if (it.isAfter(now) && s.targetScheduled) "예정" else "입면 + ${shortDuration(s.targetMinutes)}", planned = true)
    }
    return result.sortedWith(compareBy<SleepMoment> { it.at }.thenBy { it.id })
}

fun recordedEnd(s: SleepSession, logs: List<LogEntity>, now: Instant): Instant {
    s.firedAt?.let { return it }
    val terminal = logs.filter { it.sessionId == s.sessionId && it.kind in setOf("CANCEL", "DISMISS") }.maxByOrNull { it.at }
    if (!s.isActive && terminal != null) return Instant.ofEpochMilli(terminal.at)
    return if (s.isActive) now else s.receivedAt ?: s.monitorStartedAt
}
