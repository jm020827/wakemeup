package com.wakemeup.core

import java.time.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class SessionCoordinatorTest {
    private class MutableClock(var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = now
    }
    private class MemoryStore : SessionStore {
        val sessions = linkedMapOf<String, SleepSession>()
        val logs = mutableListOf<SessionLog>()
        override suspend fun latest() = sessions.values.lastOrNull()
        override suspend fun find(id: String) = sessions[id]
        override suspend fun save(session: SleepSession) { sessions[session.sessionId] = session }
        override suspend fun log(entry: SessionLog) { logs += entry }
    }
    private class FakeScheduler : AlarmScheduler {
        val alarms = mutableMapOf<Pair<String, AlarmKind>, Instant>()
        val calls = mutableListOf<String>()
        var failTarget = false
        var failBackup = false
        override fun schedule(sessionId: String, kind: AlarmKind, at: Instant) {
            calls += "schedule:$kind"
            if ((kind == AlarmKind.TARGET && failTarget) || (kind == AlarmKind.BACKUP && failBackup)) throw SecurityException("permission")
            alarms[sessionId to kind] = at
        }
        override fun cancel(sessionId: String, kind: AlarmKind) { calls += "cancel:$kind"; alarms.remove(sessionId to kind) }
    }
    private val started = Instant.parse("2026-10-06T15:00:00Z") // Seoul 00:00
    private val onset = started.plusSeconds(20 * 60)
    private val backup = started.plusSeconds(7 * 3600)
    private val clock = MutableClock(started)
    private val store = MemoryStore()
    private val alarms = FakeScheduler()
    private val commands = mutableListOf<SleepSession>()
    private val coordinator = SessionCoordinator(store, alarms, WatchBridge { commands += it }, clock)
    private suspend fun start(validation: Boolean = false) = coordinator.start(360, backup, validation, "watch-1")
    private fun event(id: String, at: Instant = onset, node: String = "watch-1") = SleepEvent(id, at, clock.now, node)

    @Test fun `3 hour delayed event uses original 0020 onset and schedules 0620`() = runTest {
        val s = start(); clock.now = onset.plusSeconds(3 * 3600)
        coordinator.receive(event(s.sessionId))
        val updated = store.latest()!!
        assertEquals(onset, updated.onsetAt); assertEquals(clock.now, updated.receivedAt)
        assertEquals(onset.plusSeconds(6 * 3600), updated.alarmAt)
        assertEquals("06:20", updated.alarmAt!!.atZone(ZoneId.of("Asia/Seoul")).toLocalTime().toString())
        assertEquals(SessionStatus.SCHEDULED, updated.status)
        assertEquals(updated.alarmAt, alarms.alarms[s.sessionId to AlarmKind.TARGET])
        assertFalse(alarms.alarms.containsKey(s.sessionId to AlarmKind.BACKUP))
    }
    @Test fun `starting monitoring never assumes sleep onset`() = runTest {
        val s = start(); assertNull(s.onsetAt); assertNull(s.alarmAt)
        assertEquals(backup, alarms.alarms[s.sessionId to AlarmKind.BACKUP])
    }
    @Test fun `duplicate and later sleep after awakening never reset onset`() = runTest {
        val s = start(); clock.now = onset.plusSeconds(3 * 3600); coordinator.receive(event(s.sessionId))
        val first = store.latest(); val calls = alarms.calls.size
        coordinator.receive(event(s.sessionId)); coordinator.receive(event(s.sessionId, onset.plusSeconds(1800)))
        assertEquals(first, store.latest()); assertEquals(calls, alarms.calls.size)
    }
    @Test fun `previous session and another watch events are ignored`() = runTest {
        val s = start(); clock.now = onset.plusSeconds(3 * 3600)
        coordinator.receive(event("old-session")); coordinator.receive(event(s.sessionId, node = "watch-2"))
        assertNull(store.latest()!!.onsetAt)
    }
    @Test fun `onset before monitoring or future timestamps are ignored`() = runTest {
        val s = start(); clock.now = onset.plusSeconds(3 * 3600)
        coordinator.receive(event(s.sessionId, started.minusSeconds(1)))
        coordinator.receive(event(s.sessionId, clock.now.plusSeconds(1)))
        coordinator.receive(SleepEvent(s.sessionId, onset, onset.minusSeconds(1), "watch-1"))
        assertNull(store.latest()!!.onsetAt)
    }
    @Test fun `past target is never scheduled and fallback remains`() = runTest {
        val s = start(); clock.now = onset.plusSeconds(6 * 3600)
        coordinator.receive(event(s.sessionId))
        assertEquals(SessionStatus.FAILED, store.latest()!!.status)
        assertEquals(onset, store.latest()!!.onsetAt)
        assertEquals(backup, alarms.alarms[s.sessionId to AlarmKind.BACKUP])
        assertFalse(alarms.calls.contains("schedule:TARGET"))
    }
    @Test fun `target scheduling failure keeps backup and locks first onset`() = runTest {
        val s = start(); clock.now = onset.plusSeconds(3 * 3600); alarms.failTarget = true
        coordinator.receive(event(s.sessionId))
        assertEquals(SessionStatus.FAILED, store.latest()!!.status)
        assertEquals(backup, alarms.alarms[s.sessionId to AlarmKind.BACKUP])
        coordinator.receive(event(s.sessionId, onset.plusSeconds(1800)))
        assertEquals(onset, store.latest()!!.onsetAt)
    }
    @Test fun `backup removed only after successful target scheduling`() = runTest {
        val s = start(); clock.now = onset.plusSeconds(3 * 3600); coordinator.receive(event(s.sessionId))
        assertTrue(alarms.calls.indexOf("schedule:TARGET") < alarms.calls.indexOf("cancel:BACKUP"))
    }
    @Test fun `backup delivery survives crash between system reservation and database confirmation`() = runTest {
        val s = start(); store.save(store.latest()!!.copy(backupScheduled = false)); clock.now = backup
        assertEquals(SessionStatus.RINGING, coordinator.ring(s.sessionId, AlarmKind.BACKUP)!!.status)
    }
    @Test fun `validation stores timestamps and never replaces backup`() = runTest {
        val s = start(validation = true); clock.now = onset.plusSeconds(3 * 3600)
        coordinator.receive(event(s.sessionId))
        assertEquals(SessionStatus.OBSERVED, store.latest()!!.status)
        assertEquals(onset.plusSeconds(6 * 3600), store.latest()!!.alarmAt)
        assertEquals(backup, alarms.alarms[s.sessionId to AlarmKind.BACKUP])
        assertFalse(alarms.calls.contains("schedule:TARGET"))
        coordinator.restore(); assertFalse(alarms.calls.contains("schedule:TARGET"))
    }
    @Test fun `cancel removes all alarms and delayed events cannot revive session`() = runTest {
        val s = start(); coordinator.cancel(); clock.now = onset.plusSeconds(3 * 3600)
        coordinator.receive(event(s.sessionId))
        assertEquals(SessionStatus.CANCELLED, store.latest()!!.status); assertTrue(alarms.alarms.isEmpty())
        assertNull(coordinator.ring(s.sessionId, AlarmKind.BACKUP))
        assertFalse(commands.last().isActive)
    }
    @Test fun `cannot replace an active session`() = runTest {
        start(); assertFailsWith<IllegalStateException> { start() }
        coordinator.cancel(); assertTrue(start().isActive)
    }
    @Test fun `reboot restores exact UTC target and suppresses stale backup`() = runTest {
        val s = start(); clock.now = onset.plusSeconds(3 * 3600); coordinator.receive(event(s.sessionId))
        alarms.alarms.clear(); coordinator.restore()
        assertEquals(onset.plusSeconds(6 * 3600), alarms.alarms[s.sessionId to AlarmKind.TARGET])
        assertNull(coordinator.ring(s.sessionId, AlarmKind.BACKUP))
    }
    @Test fun `reboot after target time does not ring immediately`() = runTest {
        val s = start(); clock.now = onset.plusSeconds(3 * 3600); coordinator.receive(event(s.sessionId))
        alarms.alarms.clear(); clock.now = onset.plusSeconds(6 * 3600 + 1); coordinator.restore()
        assertEquals(SessionStatus.FAILED, store.latest()!!.status)
        assertEquals(backup, alarms.alarms[s.sessionId to AlarmKind.BACKUP])
        assertNull(alarms.alarms[s.sessionId to AlarmKind.TARGET])
    }
    @Test fun `restore retries failed or interrupted scheduling with locked onset`() = runTest {
        val s = start(); clock.now = onset.plusSeconds(3 * 3600); alarms.failTarget = true; coordinator.receive(event(s.sessionId))
        alarms.failTarget = false; coordinator.restore()
        assertEquals(SessionStatus.SCHEDULED, store.latest()!!.status)
        assertEquals(onset.plusSeconds(6 * 3600), alarms.alarms[s.sessionId to AlarmKind.TARGET])
        val pending = store.latest()!!.copy(status = SessionStatus.SCHEDULING, targetScheduled = false)
        store.save(pending); alarms.alarms.clear(); coordinator.restore()
        assertEquals(SessionStatus.SCHEDULED, store.latest()!!.status)
    }
    @Test fun `backup can ring after late onset and dismissal completes session`() = runTest {
        val s = start(); clock.now = onset.plusSeconds(6 * 3600); coordinator.receive(event(s.sessionId))
        clock.now = backup; val ringing = coordinator.ring(s.sessionId, AlarmKind.BACKUP)!!
        assertEquals(SessionStatus.RINGING, ringing.status); assertEquals(clock.now, ringing.firedAt)
        assertNull(coordinator.ring(s.sessionId, AlarmKind.BACKUP))
        coordinator.dismiss(s.sessionId); assertEquals(SessionStatus.COMPLETED, store.latest()!!.status)
        assertTrue(alarms.alarms.isEmpty())
    }
    @Test fun `target ringing records timing error and ignores late events`() = runTest {
        val s = start(); clock.now = onset.plusSeconds(3 * 3600); coordinator.receive(event(s.sessionId))
        clock.now = onset.plusSeconds(6 * 3600 + 2); coordinator.ring(s.sessionId, AlarmKind.TARGET)
        assertTrue(store.logs.last { it.kind == "RING" }.detail.contains("오차=2000ms"))
        coordinator.receive(event(s.sessionId, onset.plusSeconds(1200)))
        assertEquals(SessionStatus.RINGING, store.latest()!!.status)
    }
    @Test fun `parallel duplicate deliveries produce one target schedule`() = runTest {
        val s = start(); clock.now = onset.plusSeconds(3 * 3600)
        coroutineScope { repeat(20) { launch { coordinator.receive(event(s.sessionId)) } } }
        assertEquals(1, alarms.calls.count { it == "schedule:TARGET" })
    }
    @Test fun `missing exact access on reboot is a visible failure`() = runTest {
        start(); alarms.failBackup = true; coordinator.restore()
        assertFalse(store.latest()!!.backupScheduled)
        assertTrue(store.latest()!!.failure!!.contains("복구 실패"))
    }
    @Test fun `watch reconnect acknowledgement preserves schedule failures`() = runTest {
        val s = start(); clock.now = onset.plusSeconds(6 * 3600); coordinator.receive(event(s.sessionId))
        val failure = store.latest()!!.failure; coordinator.monitoring(s.sessionId, true, null)
        assertEquals(failure, store.latest()!!.failure)
    }
    @Test fun `offline command sync keeps locally reserved fallback`() = runTest {
        val offline = SessionCoordinator(store, alarms, WatchBridge { throw IllegalStateException("offline") }, clock)
        val s = offline.start(360, backup, true, "watch-1")
        assertEquals(backup, alarms.alarms[s.sessionId to AlarmKind.BACKUP])
        assertTrue(store.logs.any { it.kind == "SYNC_PENDING" })
    }
    @Test fun `backup failure is persisted and not shown as successfully scheduled`() = runTest {
        alarms.failBackup = true; start()
        assertEquals(SessionStatus.FAILED, store.latest()!!.status); assertFalse(store.latest()!!.backupScheduled)
    }
    @Test fun `minimum duration and optional backup`() = runTest {
        assertFailsWith<IllegalArgumentException> { coordinator.start(359, backup, false, "watch-1") }
        val s = coordinator.start(540, null, false, "watch-1")
        assertTrue(alarms.alarms.isEmpty()); clock.now = onset.plusSeconds(3 * 3600); coordinator.receive(event(s.sessionId))
        assertEquals(onset.plusSeconds(9 * 3600), store.latest()!!.alarmAt)
    }
    @Test fun `next backup handles midnight and daylight saving in local zone`() {
        val seoul = ZoneId.of("Asia/Seoul")
        assertEquals(Instant.parse("2026-10-07T22:00:00Z"), AlarmMath.nextBackup(Instant.parse("2026-10-06T23:00:00Z"), LocalTime.of(7, 0), seoul))
        val ny = ZoneId.of("America/New_York")
        val before = Instant.parse("2026-03-07T15:00:00Z")
        assertEquals(Instant.parse("2026-03-08T11:00:00Z"), AlarmMath.nextBackup(before, LocalTime.of(7, 0), ny))
    }
}
