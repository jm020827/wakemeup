package com.wakemeup.mobile.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.google.android.gms.wearable.DataMap
import java.time.Instant
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore("settings")
data class AppSettings(val targetMinutes: Int = 450, val backupMinutes: Int? = 420, val verifiedNode: String = "")
data class WatchStatus(
    val nodeId: String = "", val name: String = "워치 연결을 기다리는 중", val supported: Boolean = false,
    val permission: Boolean = false, val monitoring: Boolean = false, val sessionId: String = "", val error: String = "워치에서 wakemeup을 열어 주세요.",
    val seenAt: Instant? = null,
)
class SettingsStore(context: Context) {
    private val store = context.settingsDataStore
    private val target = intPreferencesKey("target")
    private val backup = intPreferencesKey("backup")
    private val verified = stringPreferencesKey("verifiedNode")
    private val node = stringPreferencesKey("node")
    private val name = stringPreferencesKey("name")
    private val supported = booleanPreferencesKey("supported")
    private val permission = booleanPreferencesKey("permission")
    private val monitoring = booleanPreferencesKey("monitoring")
    private val session = stringPreferencesKey("watchSession")
    private val error = stringPreferencesKey("error")
    private val seen = longPreferencesKey("seen")
    private val commandRevision = longPreferencesKey("commandRevision")
    val settings = store.data.map { AppSettings(it[target] ?: 450, (it[backup] ?: 420).takeIf { m -> m >= 0 }, it[verified] ?: "") }
    val watch = store.data.map { WatchStatus(it[node] ?: "", it[name] ?: "워치 연결을 기다리는 중", it[supported] ?: false, it[permission] ?: false, it[monitoring] ?: false, it[session] ?: "", it[error] ?: "워치에서 wakemeup을 열어 주세요.", it[seen]?.let(Instant::ofEpochMilli)) }
    suspend fun save(minutes: Int, backupMinutes: Int?) {
        require(minutes >= 360); require(backupMinutes == null || backupMinutes in 0..1439)
        store.edit { it[target] = minutes; it[backup] = backupMinutes ?: -1 }
    }
    suspend fun verify(nodeId: String) { store.edit { it[verified] = nodeId } }
    suspend fun nextCommandRevision(): Long {
        var next = 0L
        store.edit { next = maxOf(System.currentTimeMillis(), (it[commandRevision] ?: 0L) + 1); it[commandRevision] = next }
        return next
    }
    suspend fun updateWatch(nodeId: String, data: DataMap) { store.edit {
        it[node] = nodeId; it[name] = data.getString("name") ?: "Galaxy Watch"; it[supported] = data.getBoolean("supported")
        it[permission] = data.getBoolean("permission"); it[monitoring] = data.getBoolean("monitoring")
        it[session] = data.getString("sessionId") ?: ""; it[error] = data.getString("error") ?: ""; it[seen] = System.currentTimeMillis()
        if (!data.getBoolean("supported")) it.remove(verified)
    } }
}
