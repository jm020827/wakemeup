package com.wakemeup.mobile.alarm

import android.content.*
import androidx.core.content.ContextCompat
import com.wakemeup.core.AlarmKind
import com.wakemeup.mobile.WakeMeUpApp
import kotlinx.coroutines.launch

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("sessionId") ?: return
        val kind = runCatching { AlarmKind.valueOf(intent.getStringExtra("kind") ?: "") }.getOrNull() ?: return
        val pending = goAsync()
        val app = context.applicationContext as WakeMeUpApp
        app.scope.launch {
            try {
                val session = app.coordinator.ring(id, kind) ?: return@launch
                ContextCompat.startForegroundService(context, Intent(context, AlarmPlaybackService::class.java).putExtra("sessionId", session.sessionId))
            } catch (e: Exception) { app.coordinator.record("PLAYBACK_FAILED", e.message ?: "알람 실행 실패") }
            finally { pending.finish() }
        }
    }
}
