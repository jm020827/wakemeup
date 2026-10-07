package com.wakemeup.core

import java.time.Clock
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface SessionStore {
    suspend fun latest(): SleepSession?
    suspend fun find(id: String): SleepSession?
    suspend fun save(session: SleepSession)
    suspend fun log(entry: SessionLog)
}
interface AlarmScheduler {
    fun schedule(sessionId: String, kind: AlarmKind, at: Instant)
    fun cancel(sessionId: String, kind: AlarmKind)
}
fun interface WatchBridge { suspend fun sync(session: SleepSession) }

/** One process-wide coordinator serializes UI, Data Layer, boot and alarm operations. */
class SessionCoordinator(
    private val store: SessionStore,
    private val scheduler: AlarmScheduler,
    private val watch: WatchBridge,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val mutex = Mutex()

    suspend fun start(targetMinutes: Int, backupAt: Instant?, validationOnly: Boolean, watchNodeId: String): SleepSession = mutex.withLock {
        check(store.latest()?.isActive != true) { "진행 중인 감시를 먼저 취소해 주세요." }
        val session = SleepSession(UUID.randomUUID().toString(), clock.instant(), targetMinutes, backupAt, validationOnly, watchNodeId)
        require(backupAt == null || backupAt.isAfter(clock.instant()))
        store.save(session)
        var updated = session
        if (backupAt != null) {
            updated = try {
                scheduler.schedule(session.sessionId, AlarmKind.BACKUP, backupAt)
                session.copy(backupScheduled = true)
            } catch (e: Exception) { session.copy(status = SessionStatus.FAILED, failure = "예비 알람 예약 실패: ${e.message}") }
            store.save(updated)
        }
        log(updated, "START", "감시 시작; 목표 ${targetMinutes}분; 검증 모드=$validationOnly")
        sync(updated)
        updated
    }

    suspend fun cancel() = mutex.withLock {
        val session = store.latest()?.takeIf { it.isActive } ?: return@withLock
        val cancelled = session.copy(status = SessionStatus.CANCELLED, targetScheduled = false, backupScheduled = false, watchMonitoring = false)
        // Persist the terminal state first so an already-delivered PendingIntent is harmless.
        store.save(cancelled)
        cancelAlarms(session)
        log(cancelled, "CANCEL", "감시와 세션 알람 취소")
        sync(cancelled)
    }

    suspend fun receive(event: SleepEvent) = mutex.withLock {
        val s = store.latest() ?: return@withLock
        val now = clock.instant()
        val ignored = when {
            event.sessionId != s.sessionId -> "이전 세션 이벤트"
            event.nodeId != s.watchNodeId -> "다른 워치 이벤트"
            !s.isActive || s.status == SessionStatus.RINGING -> "종료되거나 울리는 세션"
            s.onsetAt != null -> "첫 입면 고정; 중복·중간 각성 무시"
            event.onsetAt.isBefore(s.monitorStartedAt) -> "감시 시작 전 입면"
            event.onsetAt.isAfter(now) || event.watchReceivedAt.isBefore(event.onsetAt) -> "유효하지 않은 시각"
            else -> null
        }
        if (ignored != null) { log(s, "IGNORED", ignored); return@withLock }
        val at = AlarmMath.alarmAt(event.onsetAt, s.targetMinutes)
        val observed = s.copy(onsetAt = event.onsetAt, receivedAt = now, watchReceivedAt = event.watchReceivedAt, alarmAt = at)
        log(observed, "ONSET", "입면=${event.onsetAt}; 휴대폰 수신=$now; 지연=${java.time.Duration.between(event.onsetAt, now).seconds}초")
        when {
            s.validationOnly -> {
                store.save(observed.copy(status = SessionStatus.OBSERVED))
                log(observed, "VALIDATION", "수신 검증 기록; 계산된 기상=$at; 목표 알람은 예약하지 않음")
            }
            !at.isAfter(now) -> {
                store.save(observed.copy(status = SessionStatus.FAILED, failure = "목표 기상시각이 이미 지났습니다. 예비 알람을 유지합니다."))
                log(observed, "MISSED", "계산된 기상=$at; 예비 알람 유지")
            }
            else -> {
                // Durable intent before AlarmManager: restore() finishes interrupted scheduling.
                val pending = observed.copy(status = SessionStatus.SCHEDULING)
                store.save(pending)
                scheduleTarget(pending)
            }
        }
    }

    private suspend fun scheduleTarget(s: SleepSession) {
        try {
            scheduler.schedule(s.sessionId, AlarmKind.TARGET, requireNotNull(s.alarmAt))
        } catch (e: Exception) {
            store.save(s.copy(status = SessionStatus.FAILED, targetScheduled = false, failure = "목표 알람 예약 실패: ${e.message}. 예비 알람을 유지합니다."))
            log(s, "SCHEDULE_FAILED", e.message ?: "예약 실패")
            return
        }
        val scheduled = s.copy(status = SessionStatus.SCHEDULED, targetScheduled = true, backupScheduled = false, failure = null)
        store.save(scheduled)
        scheduler.cancel(s.sessionId, AlarmKind.BACKUP)
        log(scheduled, "SCHEDULED", "목표 알람=${s.alarmAt}; 예비 알람 교체 완료")
    }

    suspend fun monitoring(id: String, ready: Boolean, error: String?) = mutex.withLock {
        val s = store.latest()?.takeIf { it.sessionId == id && it.isActive && it.status != SessionStatus.RINGING } ?: return@withLock
        store.save(s.copy(watchMonitoring = ready, failure = if (s.status == SessionStatus.FAILED) s.failure else if (ready) null else error ?: s.failure))
        log(s, "WATCH", if (ready) "워치 감시 등록 완료" else error ?: "워치 감시 중단")
    }

    suspend fun restore() = mutex.withLock {
        val s = store.latest() ?: return@withLock
        if (!s.isActive || s.status == SessionStatus.RINGING) return@withLock
        val now = clock.instant()
        if (!s.validationOnly && s.alarmAt != null && s.alarmAt.isAfter(now)) {
            scheduleTarget(s)
        } else if (s.targetScheduled || s.status == SessionStatus.SCHEDULING) {
            store.save(s.copy(status = SessionStatus.FAILED, targetScheduled = false, failure = "재시작 중 목표 기상시각이 지났습니다."))
            scheduler.cancel(s.sessionId, AlarmKind.TARGET)
            log(s, "MISSED_RESTORE", "기상시각 경과; 즉시 울리지 않음")
        }
        val current = store.find(s.sessionId) ?: return@withLock
        if (!current.targetScheduled && current.backupAt != null) {
            if (current.backupAt.isAfter(now)) {
                try {
                    scheduler.schedule(current.sessionId, AlarmKind.BACKUP, current.backupAt)
                    store.save(current.copy(backupScheduled = true))
                } catch (e: Exception) {
                    store.save(current.copy(backupScheduled = false, failure = "알람 복구 실패: ${e.message}"))
                    log(current, "RESTORE_FAILED", e.message ?: "권한 확인 필요")
                }
            } else {
                store.save(current.copy(backupScheduled = false, failure = current.failure ?: "예비 알람시각이 지났습니다."))
                log(current, "BACKUP_MISSED", "지난 예비 알람을 자동 재예약하지 않음")
            }
        }
        log(current, "RESTORE", "저장된 UTC 시각으로 알람 복구")
        sync(store.find(s.sessionId) ?: current)
    }

    suspend fun ring(id: String, kind: AlarmKind): SleepSession? = mutex.withLock {
        val s = store.find(id) ?: return@withLock null
        if (!s.isActive || s.status == SessionStatus.RINGING) return@withLock null
        val valid = when (kind) {
            AlarmKind.TARGET -> !s.validationOnly && s.alarmAt != null && (s.targetScheduled || s.status == SessionStatus.SCHEDULING)
            // AlarmManager may have committed a backup immediately before a process death,
            // while its confirmation flag has not yet reached Room. The durable intent suffices.
            AlarmKind.BACKUP -> s.backupAt != null && !s.targetScheduled
        }
        if (!valid) return@withLock null
        val ringing = s.copy(status = SessionStatus.RINGING, firedAt = clock.instant(), firedKind = kind, watchMonitoring = false)
        store.save(ringing)
        cancelAlarms(s)
        val expected = if (kind == AlarmKind.TARGET) s.alarmAt else s.backupAt
        log(ringing, "RING", "종류=$kind; 예약=$expected; 실행=${ringing.firedAt}; 오차=${expected?.let { java.time.Duration.between(it, ringing.firedAt).toMillis() }}ms")
        ringing
    }

    suspend fun dismiss(id: String) = mutex.withLock {
        val s = store.find(id)?.takeIf { it.status == SessionStatus.RINGING } ?: return@withLock
        val completed = s.copy(status = SessionStatus.COMPLETED, targetScheduled = false, backupScheduled = false)
        store.save(completed)
        cancelAlarms(s)
        log(completed, "DISMISS", "사용자가 알람 해제")
        sync(completed)
    }

    suspend fun record(kind: String, detail: String) = mutex.withLock { log(store.latest(), kind, detail) }

    private fun cancelAlarms(s: SleepSession) { AlarmKind.entries.forEach { scheduler.cancel(s.sessionId, it) } }
    private suspend fun log(s: SleepSession?, kind: String, detail: String) = store.log(SessionLog(s?.sessionId, clock.instant(), kind, detail))
    private suspend fun sync(s: SleepSession) {
        try { watch.sync(s) } catch (e: Exception) { log(s, "SYNC_PENDING", "연결 복구 시 동기화: ${e.message}") }
    }
}
