package com.wakemeup.mobile.alarm

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import com.wakemeup.core.*
import com.wakemeup.mobile.MainActivity
import java.time.Instant

class PhoneAlarmScheduler(private val context: Context) : AlarmScheduler {
    private val manager = context.getSystemService(AlarmManager::class.java)
    fun exactAccess() = Build.VERSION.SDK_INT < 31 || manager.canScheduleExactAlarms()
    fun notificationAccess(): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java)
        return NotificationManagerCompat.from(context).areNotificationsEnabled() && manager.getNotificationChannel("wake_alarm")?.importance != NotificationManager.IMPORTANCE_NONE
    }
    fun fullScreenAccess() = Build.VERSION.SDK_INT < 34 || context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
    override fun schedule(sessionId: String, kind: AlarmKind, at: Instant) {
        check(exactAccess()) { "정확한 알람 접근 권한이 필요합니다." }
        check(notificationAccess()) { "알림 권한이 필요합니다." }
        check(at.isAfter(Instant.now())) { "이미 지난 알람시각입니다." }
        val show = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.setAlarmClock(AlarmManager.AlarmClockInfo(at.toEpochMilli(), show), operation(sessionId, kind))
    }
    override fun cancel(sessionId: String, kind: AlarmKind) { manager.cancel(operation(sessionId, kind)) }
    private fun operation(id: String, kind: AlarmKind): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).setData(Uri.parse("wakemeup://alarm/$id/${kind.name}"))
            .putExtra("sessionId", id).putExtra("kind", kind.name)
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}
