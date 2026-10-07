package com.wakemeup.mobile.debug

import android.content.*
import com.wakemeup.core.*
import com.wakemeup.mobile.WakeMeUpApp
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.launch

/** Explicit adb test hook, packaged only in debug APKs. Never marks a watch as verified. */
class DebugScenarioReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext as WakeMeUpApp
        app.scope.launch {
            try {
                val scenario = intent.getStringExtra("scenario") ?: "delayed"
                if (scenario == "cancel") { app.coordinator.cancel(); context.stopService(Intent(context, com.wakemeup.mobile.alarm.AlarmPlaybackService::class.java)); return@launch }
                check(app.store.latest()?.isActive != true) { "진행 중인 세션을 먼저 취소하세요." }
                val now = Instant.now()
                val onset = when (scenario) {
                    "ring" -> now.minusSeconds(6 * 3600 - 15)
                    "late" -> now.minusSeconds(6 * 3600 + 60)
                    else -> now.minusSeconds(3 * 3600)
                }
                val session = SleepSession(UUID.randomUUID().toString(), onset.minusSeconds(20 * 60), 360,
                    backupAt = now.plusSeconds(if (scenario == "late") 20 else 600), validationOnly = scenario == "validation", watchNodeId = "debug-watch")
                app.store.save(session)
                // Reserve a real fallback through the same scheduler used by production.
                app.scheduler.schedule(session.sessionId, AlarmKind.BACKUP, session.backupAt!!)
                app.store.save(session.copy(backupScheduled = true))
                val event = SleepEvent(session.sessionId, onset, now, "debug-watch")
                app.coordinator.receive(event)
                if (scenario == "duplicate") { app.coordinator.receive(event); app.coordinator.receive(event.copy(onsetAt = onset.plusSeconds(60))) }
                if (scenario == "old") app.coordinator.receive(event.copy(sessionId = "previous-session"))
                app.coordinator.record("DEBUG_SCENARIO", "주입 시나리오=$scenario; 실기기 수면 검증에 사용하지 않음")
            } catch (e: Exception) { app.coordinator.record("DEBUG_FAILED", e.message ?: "주입 실패") }
            finally { pending.finish() }
        }
    }
}
