package com.wakemeup.wear

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.google.android.gms.wearable.DataMap
import java.time.Instant
import kotlinx.coroutines.flow.*

private val Context.watchDataStore by preferencesDataStore("watch")
data class WatchState(
    val sessionId: String = "", val startedAt: Long = 0, val targetMinutes: Int = 450,
    val active: Boolean = false, val revision: Long = 0, val validation: Boolean = true,
    val supported: Boolean = false, val permission: Boolean = false, val monitoring: Boolean = false,
    val error: String = "휴대폰에서 감시를 시작해 주세요.", val onsetAt: Long = 0, val receivedAt: Long = 0,
)
class WatchStore(context: Context) {
    private val store = context.watchDataStore
    private val id = stringPreferencesKey("id"); private val started = longPreferencesKey("started")
    private val target = intPreferencesKey("target"); private val active = booleanPreferencesKey("active")
    private val revision = longPreferencesKey("revision"); private val validation = booleanPreferencesKey("validation")
    private val supported = booleanPreferencesKey("supported"); private val permission = booleanPreferencesKey("permission")
    private val monitoring = booleanPreferencesKey("monitoring"); private val error = stringPreferencesKey("error")
    private val onset = longPreferencesKey("onset"); private val received = longPreferencesKey("received")
    val state = store.data.map { WatchState(it[id] ?: "", it[started] ?: 0, it[target] ?: 450, it[active] ?: false, it[revision] ?: 0, it[validation] ?: true, it[supported] ?: false, it[permission] ?: false, it[monitoring] ?: false, it[error] ?: "휴대폰에서 감시를 시작해 주세요.", it[onset] ?: 0, it[received] ?: 0) }
    suspend fun current() = state.first()
    suspend fun command(map: DataMap) {
        require(map.getInt("targetMinutes") >= 360)
        val sessionId = requireNotNull(map.getString("sessionId"))
        val newRevision = map.getLong("revision")
        store.edit {
            if (newRevision <= (it[revision] ?: 0L)) return@edit
            if (it[id] != sessionId) { it.remove(onset); it.remove(received); it[monitoring] = false }
            it[id] = sessionId; it[started] = map.getLong("monitorStartedAt"); it[target] = map.getInt("targetMinutes")
            it[active] = map.getBoolean("active"); it[revision] = newRevision; it[validation] = map.getBoolean("validationOnly")
            if (!map.getBoolean("active")) it[monitoring] = false
        }
    }
    suspend fun health(support: Boolean, granted: Boolean, registered: Boolean, message: String) { store.edit {
        it[supported] = support; it[permission] = granted; it[monitoring] = registered; it[error] = message
    } }
    suspend fun sleep(at: Instant, now: Instant): Boolean {
        var accepted = false
        store.edit {
            if (it[active] != true || (it[onset] ?: 0L) != 0L || at.toEpochMilli() < (it[started] ?: 0L) || at.isAfter(now)) return@edit
            // Persist the first original state-change timestamp before scheduling delivery work.
            it[onset] = at.toEpochMilli(); it[received] = now.toEpochMilli(); accepted = true
        }
        return accepted
    }
}
