package com.wakemeup.mobile.data

import androidx.room.*
import com.wakemeup.core.*
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import org.json.JSONObject

@Entity(tableName = "sessions")
data class SessionEntity(@PrimaryKey val id: String, val startedAt: Long, val session: SleepSession)
@Entity(tableName = "logs")
data class LogEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val sessionId: String?, val at: Long, val kind: String, val detail: String)

class SessionConverters {
    @TypeConverter fun encode(s: SleepSession): String = JSONObject().apply {
        put("id", s.sessionId); put("start", s.monitorStartedAt.toString()); put("minutes", s.targetMinutes)
        put("backup", s.backupAt?.toString()); put("validation", s.validationOnly); put("node", s.watchNodeId)
        put("onset", s.onsetAt?.toString()); put("received", s.receivedAt?.toString()); put("watchReceived", s.watchReceivedAt?.toString())
        put("alarm", s.alarmAt?.toString()); put("status", s.status.name); put("targetScheduled", s.targetScheduled)
        put("backupScheduled", s.backupScheduled); put("watchMonitoring", s.watchMonitoring); put("failure", s.failure)
        put("fired", s.firedAt?.toString()); put("firedKind", s.firedKind?.name)
    }.toString()
    @TypeConverter fun decode(value: String): SleepSession = JSONObject(value).run {
        fun instant(key: String) = if (has(key) && !isNull(key)) Instant.parse(getString(key)) else null
        SleepSession(
            sessionId = getString("id"), monitorStartedAt = Instant.parse(getString("start")), targetMinutes = getInt("minutes"),
            backupAt = instant("backup"), validationOnly = getBoolean("validation"), watchNodeId = getString("node"),
            onsetAt = instant("onset"), receivedAt = instant("received"), watchReceivedAt = instant("watchReceived"), alarmAt = instant("alarm"),
            status = SessionStatus.valueOf(getString("status")), targetScheduled = getBoolean("targetScheduled"),
            backupScheduled = getBoolean("backupScheduled"), watchMonitoring = optBoolean("watchMonitoring"),
            failure = if (has("failure")) getString("failure") else null, firedAt = instant("fired"),
            firedKind = if (has("firedKind")) AlarmKind.valueOf(getString("firedKind")) else null,
        )
    }
}
@Dao interface SessionDao {
    @Query("SELECT * FROM sessions ORDER BY startedAt DESC, rowid DESC LIMIT 1") suspend fun latest(): SessionEntity?
    @Query("SELECT * FROM sessions WHERE id = :id") suspend fun find(id: String): SessionEntity?
    @Query("SELECT * FROM sessions ORDER BY startedAt DESC, rowid DESC LIMIT 1") fun observeLatest(): Flow<SessionEntity?>
    @Query("SELECT * FROM sessions ORDER BY startedAt DESC LIMIT 10") fun history(): Flow<List<SessionEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun save(session: SessionEntity)
    @Insert suspend fun log(log: LogEntity)
    @Query("SELECT * FROM logs ORDER BY id DESC LIMIT 150") fun logs(): Flow<List<LogEntity>>
    @Query("SELECT * FROM logs ORDER BY id") suspend fun allLogs(): List<LogEntity>
}
@Database(entities = [SessionEntity::class, LogEntity::class], version = 1, exportSchema = true)
@TypeConverters(SessionConverters::class)
abstract class AppDatabase : RoomDatabase() { abstract fun sessions(): SessionDao }

class RoomSessionStore(private val dao: SessionDao) : SessionStore {
    override suspend fun latest() = dao.latest()?.session
    override suspend fun find(id: String) = dao.find(id)?.session
    override suspend fun save(session: SleepSession) = dao.save(SessionEntity(session.sessionId, session.monitorStartedAt.toEpochMilli(), session))
    override suspend fun log(entry: SessionLog) = dao.log(LogEntity(sessionId = entry.sessionId, at = entry.at.toEpochMilli(), kind = entry.kind, detail = entry.detail))
}
