package com.wakemeup.mobile.sync

import android.content.*
import androidx.work.*
import com.wakemeup.mobile.WakeMeUpApp

class RecoveryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as WakeMeUpApp
        app.coordinator.restore()
        return try { app.dataLayer.refresh(); Result.success() } catch (_: Exception) { Result.retry() }
    }
    companion object {
        fun enqueue(context: Context) { WorkManager.getInstance(context).enqueueUniqueWork("recover", ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<RecoveryWorker>().build()) }
    }
}
class PhoneBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED")) RecoveryWorker.enqueue(context)
    }
}
