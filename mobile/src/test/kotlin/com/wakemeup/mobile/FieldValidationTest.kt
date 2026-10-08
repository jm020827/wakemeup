package com.wakemeup.mobile

import androidx.room.Room
import com.wakemeup.core.*
import com.wakemeup.mobile.data.*
import java.time.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FieldValidationTest {
    private lateinit var database: AppDatabase
    private lateinit var store: RoomSessionStore
    private lateinit var settings: SettingsStore
    private val watch = WatchStatus(nodeId = "watch", supported = true)
    private val onset = Instant.parse("2026-10-07T17:49:02.091Z")
    private val received = Instant.parse("2026-10-07T18:09:03.867616Z")
    private fun receivedSession(id: String = "overnight") = SleepSession(
        id, onset.minusSeconds(600), 360, validationOnly = true, watchNodeId = watch.nodeId,
        onsetAt = onset, receivedAt = received, watchReceivedAt = received.minusSeconds(1),
        alarmAt = AlarmMath.alarmAt(onset, 360), status = SessionStatus.OBSERVED,
    )
    private val scheduler = object : AlarmScheduler {
        override fun schedule(sessionId: String, kind: AlarmKind, at: Instant) {}
        override fun cancel(sessionId: String, kind: AlarmKind) {}
    }
    @Before fun open() {
        val context = RuntimeEnvironment.getApplication()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        store = RoomSessionStore(database.sessions())
        settings = SettingsStore(context)
    }
    @After fun close() { database.close() }

    @Test fun cancelledReceiptCanBeValidatedAfterMoreThanTenNewEmptySessions() = runBlocking {
        val overnight = receivedSession()
        store.save(overnight)
        repeat(12) { index ->
            val clock = Clock.fixed(received.plusSeconds((index + 1L) * 3600), ZoneOffset.UTC)
            val coordinator = SessionCoordinator(store, scheduler, WatchBridge {}, clock)
            coordinator.cancel()
            coordinator.start(450, null, true, watch.nodeId)
        }
        val current = store.latest()!!
        assertNull(current.onsetAt)
        assertEquals(SessionStatus.CANCELLED, store.find(overnight.sessionId)!!.status)
        val history = database.sessions().history().first().map { it.session }
        val home = HomeState(watch = watch, session = current, history = history)
        assertEquals(13, history.size)
        assertEquals(listOf(overnight.sessionId), home.validationSessions.map { it.sessionId })

        FieldValidation(store, settings).save(home.validationSessions.single().sessionId, watch, "입면·기상 전 수신·알람 소리와 진동 확인", true)

        assertEquals(watch.nodeId, settings.settings.first().verifiedNode)
        assertEquals(current, store.latest())
        assertEquals(overnight.sessionId, database.sessions().allLogs().last().sessionId)
        assertEquals("FIELD_VALIDATION", database.sessions().allLogs().last().kind)
    }

    @Test fun selectingAnOlderReceiptUsesThatSessionAndRechecksStoredEligibility() = runBlocking {
        val first = receivedSession().copy(status = SessionStatus.COMPLETED)
        val second = first.copy(sessionId = "later", monitorStartedAt = first.monitorStartedAt.plusSeconds(86400),
            onsetAt = first.onsetAt!!.plusSeconds(86400), receivedAt = first.receivedAt!!.plusSeconds(86400),
            watchReceivedAt = first.watchReceivedAt!!.plusSeconds(86400), alarmAt = first.alarmAt!!.plusSeconds(86400))
        store.save(first); store.save(second)
        val validation = FieldValidation(store, settings)
        validation.save(first.sessionId, watch, "첫날 실기기 테스트 확인", true)
        assertEquals(first.sessionId, database.sessions().allLogs().single().sessionId)
        assertEquals(second, store.latest())

        store.save(second.copy(receivedAt = second.alarmAt))
        assertTrue(runCatching { validation.save(second.sessionId, watch, "오래된 화면에서 저장 시도", true) }.isFailure)
        assertEquals(1, database.sessions().allLogs().size)
    }

    @Test fun incompleteLateDifferentWatchAndUnconfirmedRecordsDoNotActivateAlarms() = runBlocking {
        settings.verify("")
        val valid = receivedSession()
        val invalid = listOf(
            valid.copy(validationOnly = false), valid.copy(onsetAt = null), valid.copy(receivedAt = null),
            valid.copy(alarmAt = null), valid.copy(receivedAt = valid.alarmAt),
            valid.copy(receivedAt = valid.alarmAt!!.plusSeconds(1)), valid.copy(watchNodeId = "other"),
        )
        val validation = FieldValidation(store, settings)
        for (record in invalid) {
            store.save(record)
            assertFalse(record.canValidateFor(watch))
            assertTrue(runCatching { validation.save(record.sessionId, watch, "확인", true) }.isFailure)
        }
        store.save(valid)
        assertTrue(runCatching { validation.save("missing", watch, "확인", true) }.isFailure)
        assertTrue(runCatching { validation.save(valid.sessionId, watch.copy(supported = false), "확인", true) }.isFailure)
        assertTrue(runCatching { validation.save(valid.sessionId, watch, "확인", false) }.isFailure)
        assertTrue(runCatching { validation.save(valid.sessionId, watch, "", true) }.isFailure)
        assertEquals("", settings.settings.first().verifiedNode)
        assertTrue(database.sessions().allLogs().isEmpty())
    }
}
