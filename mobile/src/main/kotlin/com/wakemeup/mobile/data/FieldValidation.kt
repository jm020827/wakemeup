package com.wakemeup.mobile.data

import com.wakemeup.core.SessionLog
import com.wakemeup.core.SessionStore
import com.wakemeup.core.SleepSession
import java.time.Clock

fun SleepSession.isValidationRecordFor(nodeId: String): Boolean =
    nodeId.isNotBlank() && validationOnly && watchNodeId == nodeId && onsetAt != null && receivedAt != null

fun SleepSession.canValidateFor(watch: WatchStatus): Boolean =
    isValidationRecordFor(watch.nodeId) && watch.supported && alarmAt?.isAfter(receivedAt) == true

class FieldValidation(
    private val sessions: SessionStore,
    private val settings: SettingsStore,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun save(sessionId: String, watch: WatchStatus, notes: String, confirmed: Boolean) {
        val session = sessions.find(sessionId)
        check(confirmed && session?.canValidateFor(watch) == true) {
            "기상 전 수신된 실제 워치 이벤트와 검증 확인이 필요합니다."
        }
        require(notes.isNotBlank()) { "입면 추정 차이·배터리·소리/진동 검증 결과를 입력해 주세요." }
        sessions.log(SessionLog(sessionId, clock.instant(), "FIELD_VALIDATION", notes))
        settings.verify(watch.nodeId)
    }
}
