package com.wakemeup.wear

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.health.services.client.HealthServices
import androidx.health.services.client.data.*
import androidx.work.*
import com.google.android.gms.wearable.*
import com.wakemeup.core.Protocol
import kotlinx.coroutines.*
import kotlinx.coroutines.guava.await as awaitFuture
import kotlinx.coroutines.tasks.await

class RegistrationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val store = (applicationContext as WatchApp).store
        val permission = ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED
        return try {
            withTimeout(45_000) {
                // Reconcile retained phone commands after installation, reconnection or reboot.
                runCatching {
                    val localId = Wearable.getNodeClient(applicationContext).localNode.await().id
                    val items = Wearable.getDataClient(applicationContext).dataItems.await()
                    try { items.filter { it.uri.path == Protocol.COMMAND }.forEach {
                        val map = DataMapItem.fromDataItem(it).dataMap
                        if (map.getInt("version") == Protocol.VERSION && map.getString("watchNodeId") == localId) store.command(map)
                    } } finally { items.release() }
                }
                val client = HealthServices.getClient(applicationContext).passiveMonitoringClient
                val capabilities = client.getCapabilitiesAsync().awaitFuture()
                val supports = UserActivityState.USER_ACTIVITY_ASLEEP in capabilities.supportedUserActivityStates
                val state = store.current()
                if (state.active && supports && permission) {
                    val config = PassiveListenerConfig.builder().setShouldUserActivityInfoBeRequested(true).build()
                    client.setPassiveListenerServiceAsync(SleepListenerService::class.java, config).awaitFuture()
                    store.health(true, true, true, "")
                } else {
                    client.clearPassiveListenerServiceAsync().awaitFuture()
                    store.health(supports, permission, false, when {
                        !supports -> "이 워치는 수면 상태를 지원하지 않습니다."
                        !permission -> "신체 활동 권한을 허용해 주세요."
                        else -> "휴대폰에서 감시를 시작해 주세요."
                    })
                }
                DeliveryWorker.enqueue(applicationContext)
                Result.success()
            }
        } catch (e: Exception) {
            val s = store.current()
            store.health(s.supported, permission, false, "Health Services 확인 실패: ${e.message}")
            DeliveryWorker.enqueue(applicationContext)
            if (runAttemptCount < 5) Result.retry() else Result.failure()
        }
    }
    companion object {
        fun enqueue(context: Context) { WorkManager.getInstance(context).enqueueUniqueWork("register-sleep", ExistingWorkPolicy.APPEND_OR_REPLACE, OneTimeWorkRequestBuilder<RegistrationWorker>().build()) }
    }
}

class DeliveryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        val state = (applicationContext as WatchApp).store.current()
        val client = Wearable.getDataClient(applicationContext)
        client.putDataItem(PutDataMapRequest.create(Protocol.STATUS).apply {
            dataMap.putInt("version", Protocol.VERSION); dataMap.putString("name", "${Build.MANUFACTURER} ${Build.MODEL}")
            dataMap.putBoolean("supported", state.supported); dataMap.putBoolean("permission", state.permission)
            dataMap.putBoolean("monitoring", state.monitoring); dataMap.putString("sessionId", state.sessionId)
            dataMap.putString("error", state.error); dataMap.putLong("updatedAt", System.currentTimeMillis())
        }.asPutDataRequest().setUrgent()).await()
        if (state.onsetAt > 0) {
            client.putDataItem(PutDataMapRequest.create(Protocol.SLEEP + state.sessionId).apply {
                dataMap.putInt("version", Protocol.VERSION); dataMap.putString("sessionId", state.sessionId)
                dataMap.putLong("onsetAt", state.onsetAt); dataMap.putLong("watchReceivedAt", state.receivedAt)
            }.asPutDataRequest().setUrgent()).await()
        }
        Result.success()
    } catch (_: Exception) { Result.retry() }
    companion object {
        fun enqueue(context: Context) { WorkManager.getInstance(context).enqueueUniqueWork("deliver-sleep", ExistingWorkPolicy.APPEND_OR_REPLACE, OneTimeWorkRequestBuilder<DeliveryWorker>().build()) }
    }
}

class WatchBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) RegistrationWorker.enqueue(context)
    }
}
