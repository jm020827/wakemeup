package com.wakemeup.mobile

import com.wakemeup.core.*
import com.wakemeup.mobile.data.*
import com.wakemeup.mobile.ui.*
import java.time.*
import org.junit.Assert.*
import org.junit.Test

class SleepPresentationTest {
    private val start = Instant.parse("2025-01-14T15:10:00Z")
    private val onset = start.plusSeconds(900)
    private val session = SleepSession("night", start, 360, validationOnly = false, watchNodeId = "watch",
        onsetAt = onset, receivedAt = onset.plusSeconds(1201), watchReceivedAt = onset.plusSeconds(1200),
        alarmAt = onset.plusSeconds(6 * 3600), status = SessionStatus.SCHEDULED, targetScheduled = true, watchMonitoring = true)
    private val ready = HomeState(watch = WatchStatus(nodeId = "watch", supported = true, permission = true),
        device = DeviceState(connectedNodes = setOf("watch"), exact = true, notifications = true, fullScreen = true, permissionsChecked = true))

    @Test fun firstLaunchRequestsPermissionsInOrderAndMissingRequiredAccessStillBlocks() {
        val empty = HomeState()
        assertEquals(SetupStep.NOTIFICATIONS, setupStep(empty))
        val notifications = empty.copy(device = DeviceState(notifications = true))
        assertEquals(SetupStep.EXACT, setupStep(notifications))
        val exact = notifications.copy(device = notifications.device.copy(exact = true))
        assertEquals(SetupStep.LOCK_SCREEN, setupStep(exact))
        val skipped = exact.copy(settings = AppSettings(onboardingComplete = true))
        assertEquals(SetupStep.DONE, setupStep(skipped))
        assertEquals(SetupStep.EXACT, setupStep(skipped.copy(device = skipped.device.copy(exact = false))))
        assertFalse(skipped.canStart)
    }
    @Test fun supportedConnectedWatchCanStartWithoutASeparateValidationGate() {
        assertTrue(ready.canStart)
        assertFalse(ready.copy(watch = ready.watch.copy(permission = false)).canStart)
        assertFalse(ready.copy(watch = ready.watch.copy(supported = false)).canStart)
        assertFalse(ready.copy(device = ready.device.copy(connectedNodes = emptySet())).canStart)
    }
    @Test fun listeningRequiresRegistrationAcknowledgementAndAConnection() {
        val waiting = session.copy(onsetAt = null, receivedAt = null, alarmAt = null, status = SessionStatus.MONITORING, targetScheduled = false)
        assertEquals(NightPhase.WAITING, nightPhase(ready.copy(session = waiting)))
        assertEquals(NightPhase.PREPARING, nightPhase(ready.copy(session = waiting.copy(watchMonitoring = false))))
        assertEquals(NightPhase.DISCONNECTED, nightPhase(ready.copy(session = waiting, device = ready.device.copy(connectedNodes = emptySet()))))
        assertEquals(NightPhase.ATTENTION, nightPhase(ready.copy(session = waiting.copy(failure = "permission"))))
    }
    @Test fun reservedPhoneAlarmRemainsVisibleWhenWatchDisconnects() {
        assertEquals(NightPhase.SCHEDULED, nightPhase(ready.copy(session = session, device = ready.device.copy(connectedNodes = emptySet()))))
        assertEquals(NightPhase.RINGING, nightPhase(ready.copy(session = session.copy(status = SessionStatus.RINGING))))
    }
    @Test fun timelineUsesStoredOnsetAndReceiptRatherThanReceiptAsOnset() {
        val moments = sleepMoments(session, emptyList(), onset.plusSeconds(3600), ZoneId.of("Asia/Seoul"))
        assertEquals(onset, moments.single { it.kind == MomentKind.ONSET }.at)
        assertEquals(session.receivedAt, moments.single { it.kind == MomentKind.RECEIVED }.at)
        assertEquals("20분 1초", receiptDelay(session))
        assertEquals("00:25:00", clockTime(onset, ZoneId.of("Asia/Seoul"), seconds = true))
        val other = session.copy(onsetAt = onset.plusSeconds(3900), receivedAt = onset.plusSeconds(3900 + 61))
        assertEquals("01:30:00", clockTime(other.onsetAt, ZoneId.of("Asia/Seoul"), seconds = true))
        assertEquals("1분 1초", receiptDelay(other))
    }
    @Test fun expectedWakeIsClearlySeparateFromAnActualBackupAlarm() {
        val fired = session.copy(firedKind = AlarmKind.BACKUP, firedAt = session.alarmAt!!.plusSeconds(1800), status = SessionStatus.COMPLETED)
        val moments = sleepMoments(fired, emptyList(), fired.firedAt!!)
        assertTrue(moments.single { it.kind == MomentKind.PLANNED }.planned)
        assertEquals("예비 알람 울림", moments.single { it.kind == MomentKind.ALARM }.title)
        assertEquals(fired.firedAt, recordedEnd(fired, emptyList(), Instant.now()))
    }
    @Test fun timelineOmitsRepeatedTechnicalEventsAndKeepsImportantEventsInTimeOrder() {
        val logs = listOf(
            LogEntity(1, session.sessionId, session.receivedAt!!.toEpochMilli(), "SCHEDULED", "first"),
            LogEntity(2, session.sessionId, session.receivedAt!!.plusSeconds(5).toEpochMilli(), "SCHEDULED", "restored"),
            LogEntity(3, session.sessionId, start.toEpochMilli(), "WATCH", "registered"),
            LogEntity(4, "other", start.toEpochMilli(), "CANCEL", "unrelated"),
        )
        val moments = sleepMoments(session, logs, onset.plusSeconds(3600))
        assertEquals(1, moments.count { it.kind == MomentKind.RESERVED })
        assertFalse(moments.any { it.kind == MomentKind.END })
        assertEquals(moments.map { it.at }.sorted(), moments.map { it.at })
    }
    @Test fun newEmptySessionsNeverHidePreviousReceivedRecordsOrChangeStoredTimes() {
        val past = session.copy(status = SessionStatus.CANCELLED, validationOnly = true)
        val empty = (1..12).map { session.copy(sessionId = "empty-$it", monitorStartedAt = start.plusSeconds(it * 3600L), onsetAt = null, receivedAt = null, status = SessionStatus.CANCELLED) }
        val visible = visibleSleepSessions(empty + past)
        assertEquals(listOf(past), visible)
        assertEquals(onset, visible.single().onsetAt)
    }
    @Test fun endingOneNightDoesNotBorrowAnotherNightsCancellationTime() {
        val ended = session.copy(status = SessionStatus.CANCELLED)
        val logs = listOf(LogEntity(1, "other", start.plusSeconds(86400).toEpochMilli(), "CANCEL", "other"),
            LogEntity(2, ended.sessionId, onset.plusSeconds(7200).toEpochMilli(), "CANCEL", "this"))
        assertEquals(onset.plusSeconds(7200), recordedEnd(ended, logs, Instant.now()))
    }
}
