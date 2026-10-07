package com.wakemeup.wear

import androidx.health.services.client.PassiveListenerService
import androidx.health.services.client.data.UserActivityInfo
import androidx.health.services.client.data.UserActivityState
import java.time.Instant
import kotlinx.coroutines.*

class SleepListenerService : PassiveListenerService() {
    override fun onUserActivityInfoReceived(info: UserActivityInfo) {
        if (info.userActivityState != UserActivityState.USER_ACTIVITY_ASLEEP) return
        runBlocking(Dispatchers.IO) {
            if ((application as WatchApp).store.sleep(info.stateChangeTime, Instant.now())) DeliveryWorker.enqueue(this@SleepListenerService)
        }
    }
    override fun onPermissionLost() {
        runBlocking(Dispatchers.IO) {
            val store = (application as WatchApp).store
            store.health(store.current().supported, false, false, "신체 활동 권한이 해제되어 감시가 중단되었습니다.")
            DeliveryWorker.enqueue(this@SleepListenerService)
        }
    }
}
