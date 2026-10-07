package com.wakemeup.core

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

enum class SessionStatus { MONITORING, OBSERVED, SCHEDULING, SCHEDULED, FAILED, RINGING, COMPLETED, CANCELLED }
enum class AlarmKind { TARGET, BACKUP }

data class SleepSession(
    val sessionId: String,
    val monitorStartedAt: Instant,
    val targetMinutes: Int,
    val backupAt: Instant? = null,
    val validationOnly: Boolean = true,
    val watchNodeId: String = "",
    val onsetAt: Instant? = null,
    val receivedAt: Instant? = null,
    val watchReceivedAt: Instant? = null,
    val alarmAt: Instant? = null,
    val status: SessionStatus = SessionStatus.MONITORING,
    val targetScheduled: Boolean = false,
    val backupScheduled: Boolean = false,
    val watchMonitoring: Boolean = false,
    val failure: String? = null,
    val firedAt: Instant? = null,
    val firedKind: AlarmKind? = null,
) {
    init { require(targetMinutes >= 360) { "목표 시간은 6시간 이상이어야 합니다." } }
    val isActive: Boolean get() = status != SessionStatus.COMPLETED && status != SessionStatus.CANCELLED
}

data class SleepEvent(val sessionId: String, val onsetAt: Instant, val watchReceivedAt: Instant, val nodeId: String)
data class SessionLog(val sessionId: String?, val at: Instant, val kind: String, val detail: String)

object AlarmMath {
    fun alarmAt(onsetAt: Instant, targetMinutes: Int): Instant {
        require(targetMinutes >= 360)
        return onsetAt.plusSeconds(targetMinutes.toLong() * 60)
    }
    fun nextBackup(now: Instant, time: LocalTime, zone: ZoneId): Instant {
        val today = now.atZone(zone).toLocalDate()
        val candidate = today.atTime(time).atZone(zone).toInstant()
        return if (candidate.isAfter(now)) candidate else today.plusDays(1).atTime(time).atZone(zone).toInstant()
    }
}

object Protocol {
    const val COMMAND = "/wakemeup/command"
    const val STATUS = "/wakemeup/status"
    const val SLEEP = "/wakemeup/sleep/"
    const val VERSION = 1
}
