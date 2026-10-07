package com.wakemeup.wear

import com.google.android.gms.wearable.DataMap
import com.wakemeup.core.Protocol
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WatchPersistenceTest {
    private val store get() = (RuntimeEnvironment.getApplication() as WatchApp).store
    private fun command(id: String, at: Instant, revision: Long, active: Boolean = true) = DataMap().apply {
        putInt("version", Protocol.VERSION); putString("sessionId", id); putLong("monitorStartedAt", at.toEpochMilli())
        putInt("targetMinutes", 360); putBoolean("active", active); putBoolean("validationOnly", true); putLong("revision", revision)
    }
    @Test fun originalOnsetIsDurableAndDuplicatesNeverReplaceIt() = runBlocking {
        val now = Instant.now(); val onset = now.minusSeconds(3 * 3600); val id = UUID.randomUUID().toString()
        store.command(command(id, onset.minusSeconds(1200), store.current().revision + 1))
        assertTrue(store.sleep(onset, now)); assertFalse(store.sleep(onset.plusSeconds(600), now))
        val persisted = WatchStore(RuntimeEnvironment.getApplication()).current()
        assertEquals(onset.toEpochMilli(), persisted.onsetAt); assertEquals(now.toEpochMilli(), persisted.receivedAt)
    }
    @Test fun oldCommandCannotResurrectCancelledSession() = runBlocking {
        val now = Instant.now(); val id = UUID.randomUUID().toString()
        val base = store.current().revision
        store.command(command(id, now, base + 100)); store.command(command(id, now, base + 102, active = false)); store.command(command(id, now, base + 101))
        assertFalse(store.current().active); assertFalse(store.sleep(now, now))
    }
    @Test fun newSessionClearsPreviousOnsetAndRejectedOldStateChangesStayEmpty() = runBlocking {
        val now = Instant.now(); val first = UUID.randomUUID().toString()
        val base = store.current().revision
        store.command(command(first, now.minusSeconds(4 * 3600), base + 1)); assertTrue(store.sleep(now.minusSeconds(3 * 3600), now))
        val second = UUID.randomUUID().toString(); store.command(command(second, now, base + 2))
        assertEquals(0L, store.current().onsetAt); assertFalse(store.sleep(now.minusSeconds(1), now)); assertFalse(store.sleep(now.plusSeconds(1), now))
        assertTrue("state=${store.current()}", store.sleep(now, now)); assertEquals(second, store.current().sessionId)
    }
    @Test fun resyncingSameSessionPreservesDurableOutbox() = runBlocking {
        val now = Instant.now(); val id = UUID.randomUUID().toString()
        val base = store.current().revision
        store.command(command(id, now.minusSeconds(4 * 3600), base + 1)); assertTrue(store.sleep(now.minusSeconds(3 * 3600), now))
        val before = store.current(); store.command(command(id, now.minusSeconds(4 * 3600), base + 2))
        assertEquals(before.onsetAt, store.current().onsetAt); assertEquals(before.receivedAt, store.current().receivedAt)
    }
    @Test fun lostPermissionIsPersistedAsNotMonitoring() = runBlocking {
        store.health(true, true, true, ""); store.health(true, false, false, "권한 해제")
        val persisted = WatchStore(RuntimeEnvironment.getApplication()).current()
        assertFalse(persisted.permission); assertFalse(persisted.monitoring); assertEquals("권한 해제", persisted.error)
    }
}
