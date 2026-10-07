package com.wakemeup.mobile.alarm

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.media.*
import android.os.*
import androidx.core.app.NotificationCompat
import com.wakemeup.core.SessionStatus
import com.wakemeup.mobile.R
import com.wakemeup.mobile.WakeMeUpApp
import kotlinx.coroutines.launch

class AlarmPlaybackService : Service() {
    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null
    private var focus: AudioFocusRequest? = null
    private var currentId: String? = null
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
    override fun onBind(intent: Intent?) = null
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "기상 알람", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "입면 기준 기상 및 예비 알람"; setSound(null, null); enableVibration(false); lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        })
        val notification = notification()
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(ID, notification)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as WakeMeUpApp
        if (intent?.action == DISMISS) {
            stopPlayback()
            app.scope.launch {
                val id = intent.getStringExtra("sessionId") ?: currentId ?: app.store.latest()?.sessionId
                if (id != null) app.coordinator.dismiss(id)
                stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
            }
            return START_NOT_STICKY
        }
        app.scope.launch {
            val session = intent?.getStringExtra("sessionId")?.let { app.store.find(it) } ?: app.store.latest()
            if (session?.status != SessionStatus.RINGING) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return@launch }
            currentId = session.sessionId
            try {
                if (ringtone == null) startPlayback()
                getSystemService(NotificationManager::class.java).notify(ID, notification())
                app.dataLayer.sync(session)
            } catch (e: Exception) { app.coordinator.record("PLAYBACK_ERROR", e.message ?: "알람 출력 확인 필요") }
        }
        return START_STICKY
    }
    private fun notification(): Notification {
        val activity = PendingIntent.getActivity(this, 1, Intent(this, AlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val dismiss = PendingIntent.getService(this, 2, Intent(this, AlarmPlaybackService::class.java).setAction(DISMISS).putExtra("sessionId", currentId), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_alarm).setContentTitle("좋은 아침이에요")
            .setContentText("wakemeup 기상 알람 · 눌러서 해제").setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX).setVisibility(NotificationCompat.VISIBILITY_PUBLIC).setOngoing(true)
            .setContentIntent(activity).setFullScreenIntent(activity, true).addAction(R.drawable.ic_alarm, "알람 해제", dismiss).build()
    }
    private fun startPlayback() {
        val audio = getSystemService(AudioManager::class.java)
        focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE).setAudioAttributes(attributes).build().also { audio.requestAudioFocus(it) }
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        ringtone = RingtoneManager.getRingtone(this, uri)?.apply { audioAttributes = attributes; if (Build.VERSION.SDK_INT >= 28) isLooping = true; play() }
        // Ringtone looping is unavailable on API 26–27: use a MediaPlayer fallback there.
        if (Build.VERSION.SDK_INT < 28) { ringtone?.stop(); ringtone = null; startLegacyPlayer(uri) }
        vibrator = if (Build.VERSION.SDK_INT >= 31) getSystemService(VibratorManager::class.java).defaultVibrator else getSystemService(Vibrator::class.java)
        vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 700, 400, 700, 800), 0), attributes)
    }
    private var legacyPlayer: MediaPlayer? = null
    private fun startLegacyPlayer(uri: android.net.Uri) {
        legacyPlayer = MediaPlayer().apply { setAudioAttributes(attributes); setDataSource(this@AlarmPlaybackService, uri); isLooping = true; setWakeMode(this@AlarmPlaybackService, PowerManager.PARTIAL_WAKE_LOCK); prepare(); start() }
    }
    private fun stopPlayback() {
        ringtone?.stop(); ringtone = null; legacyPlayer?.release(); legacyPlayer = null; vibrator?.cancel()
        focus?.let { getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) }; focus = null
    }
    override fun onDestroy() { stopPlayback(); super.onDestroy() }
    companion object {
        const val DISMISS = "com.wakemeup.DISMISS"
        private const val CHANNEL = "wake_alarm"
        private const val ID = 1001
    }
}
