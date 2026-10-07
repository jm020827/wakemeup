package com.wakemeup.mobile

import android.app.AlarmManager
import android.app.NotificationManager
import androidx.room.Room
import com.wakemeup.core.*
import com.wakemeup.mobile.alarm.PhoneAlarmScheduler
import com.wakemeup.mobile.data.*
import java.time.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidIntegrationTest {
    private val context get() = RuntimeEnvironment.getApplication()
    @Before fun permissions() { ShadowAlarmManager.setCanScheduleExactAlarms(true) }

    @Test fun roomPersistsOriginalTimestampsAndFailureAcrossReopening() = runBlocking {
        val now = Instant.now()
        val s = SleepSession("saved", now.minusSeconds(4 * 3600), 450, backupAt = now.plusSeconds(3600), validationOnly = false,
            watchNodeId = "watch", onsetAt = now.minusSeconds(3 * 3600), receivedAt = now, watchReceivedAt = now.minusSeconds(10),
            alarmAt = now.plusSeconds(450 * 60 - 3 * 3600), status = SessionStatus.FAILED, backupScheduled = true, failure = "permission")
        fun open() = Room.databaseBuilder(context, AppDatabase::class.java, "reopen.db").build()
        val first = open(); RoomSessionStore(first.sessions()).save(s); first.close()
        val second = open()
        assertEquals(s, RoomSessionStore(second.sessions()).latest()); second.close()
    }
    @Test fun nullableSessionFieldsSurviveRoomConverter() {
        val s = SleepSession("empty", Instant.ofEpochMilli(1000), 360)
        val converters = SessionConverters()
        assertEquals(s, converters.decode(converters.encode(s)))
        val ringing = s.copy(status = SessionStatus.RINGING, firedKind = AlarmKind.BACKUP, firedAt = Instant.ofEpochMilli(2000))
        assertEquals(ringing, converters.decode(converters.encode(ringing)))
    }
    @Test fun alarmClockReservationsAreUniqueAndCancelledIndependently() {
        val scheduler = PhoneAlarmScheduler(context)
        val now = Instant.now()
        scheduler.schedule("one", AlarmKind.BACKUP, now.plusSeconds(60))
        scheduler.schedule("one", AlarmKind.TARGET, now.plusSeconds(120))
        scheduler.schedule("two", AlarmKind.BACKUP, now.plusSeconds(180))
        val shadow = Shadows.shadowOf(context.getSystemService(AlarmManager::class.java))
        assertEquals(3, shadow.scheduledAlarms.size)
        assertEquals(now.plusSeconds(60).toEpochMilli(), context.getSystemService(AlarmManager::class.java).nextAlarmClock.triggerTime)
        scheduler.cancel("one", AlarmKind.BACKUP)
        assertEquals(2, shadow.scheduledAlarms.size)
        scheduler.cancel("one", AlarmKind.TARGET)
        assertEquals(1, shadow.scheduledAlarms.size)
    }
    @Test fun deniedExactAccessPreventsReservation() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        val scheduler = PhoneAlarmScheduler(context)
        assertFalse(scheduler.exactAccess())
        assertThrows(IllegalStateException::class.java) { scheduler.schedule("denied", AlarmKind.TARGET, Instant.now().plusSeconds(60)) }
        assertTrue(Shadows.shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms.isEmpty())
    }
    @Test fun disabledNotificationsPreventReservation() {
        Shadows.shadowOf(context.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(false)
        val scheduler = PhoneAlarmScheduler(context)
        assertFalse(scheduler.notificationAccess())
        assertThrows(IllegalStateException::class.java) { scheduler.schedule("denied", AlarmKind.BACKUP, Instant.now().plusSeconds(60)) }
    }
    @Test fun delayedEventFlowsThroughRoomIntoRealAlarmManager() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val store = RoomSessionStore(database.sessions())
            val scheduler = PhoneAlarmScheduler(context)
            val now = Instant.now()
            val onset = now.minusSeconds(3 * 3600)
            val session = SleepSession("delayed", onset.minusSeconds(1200), 360, now.plusSeconds(4 * 3600), false, "watch")
            store.save(session)
            scheduler.schedule(session.sessionId, AlarmKind.BACKUP, session.backupAt!!)
            store.save(session.copy(backupScheduled = true))
            val coordinator = SessionCoordinator(store, scheduler, WatchBridge {}, Clock.fixed(now, ZoneOffset.UTC))
            coordinator.receive(SleepEvent(session.sessionId, onset, now, "watch"))
            val saved = store.latest()!!
            assertEquals(SessionStatus.SCHEDULED, saved.status)
            assertEquals(onset.plusSeconds(6 * 3600), saved.alarmAt)
            val shadow = Shadows.shadowOf(context.getSystemService(AlarmManager::class.java))
            assertEquals(1, shadow.scheduledAlarms.size)
            assertEquals(saved.alarmAt!!.toEpochMilli(), context.getSystemService(AlarmManager::class.java).nextAlarmClock.triggerTime)
            coordinator.cancel(); assertTrue(shadow.scheduledAlarms.isEmpty())
        } finally { database.close() }
    }
}
