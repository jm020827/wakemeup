package com.wakemeup.mobile

import android.app.Application
import androidx.room.Room
import com.wakemeup.core.SessionCoordinator
import com.wakemeup.mobile.alarm.PhoneAlarmScheduler
import com.wakemeup.mobile.data.*
import com.wakemeup.mobile.sync.PhoneDataLayer
import kotlinx.coroutines.*

class WakeMeUpApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val database by lazy { Room.databaseBuilder(this, AppDatabase::class.java, "wakemeup.db").build() }
    val store by lazy { RoomSessionStore(database.sessions()) }
    val settings by lazy { SettingsStore(this) }
    val dataLayer by lazy { PhoneDataLayer(this) }
    val scheduler by lazy { PhoneAlarmScheduler(this) }
    val coordinator by lazy { SessionCoordinator(store, scheduler, dataLayer) }
}
