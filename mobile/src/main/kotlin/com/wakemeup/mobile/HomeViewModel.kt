package com.wakemeup.mobile

import android.app.Application
import androidx.lifecycle.*
import com.wakemeup.core.*
import com.wakemeup.mobile.data.*
import java.time.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class DeviceState(val connectedNodes: Set<String> = emptySet(), val exact: Boolean = false, val notifications: Boolean = false, val fullScreen: Boolean = false, val busy: Boolean = false, val message: String? = null)
data class HomeState(val settings: AppSettings = AppSettings(), val watch: WatchStatus = WatchStatus(), val session: SleepSession? = null, val logs: List<LogEntity> = emptyList(), val device: DeviceState = DeviceState(), val history: List<SleepSession> = emptyList()) {
    val watchConnected get() = watch.nodeId in device.connectedNodes
    val verified get() = watch.nodeId.isNotBlank() && settings.verifiedNode == watch.nodeId
    val canStart get() = device.exact && device.notifications && watchConnected && watch.supported && watch.permission && session?.isActive != true && !device.busy
    val validationSessions get() = history.filter { it.isValidationRecordFor(watch.nodeId) }
}
class HomeViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as WakeMeUpApp
    private val device = MutableStateFlow(DeviceState())
    init { viewModelScope.launch { app.dataLayer.connectionState.collect { nodes -> device.update { it.copy(connectedNodes = nodes) } } } }
    val state = combine(app.settings.settings, app.settings.watch, app.database.sessions().history(), app.database.sessions().logs(), device) { settings, watch, history, logs, device ->
        HomeState(settings, watch, history.firstOrNull()?.session, logs, device, history.map { it.session })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HomeState())
    fun refresh() = run {
        device.update { it.copy(exact = app.scheduler.exactAccess(), notifications = app.scheduler.notificationAccess(), fullScreen = app.scheduler.fullScreenAccess()) }
        viewModelScope.launch {
            try { device.update { it.copy(connectedNodes = app.dataLayer.connected()) }; app.dataLayer.refresh() }
            catch (e: Exception) { app.dataLayer.updateConnections(emptySet()); device.update { it.copy(connectedNodes = emptySet(), message = "워치 연결 확인: ${e.message}") } }
        }
    }
    fun save(minutes: Int, backup: Int?) = action { app.settings.save(minutes, backup) }
    fun start(validation: Boolean) = action {
        val s = state.value
        check(s.copy(device = s.device.copy(busy = false)).canStart) { "필수 권한과 워치 연결을 확인해 주세요." }
        check(validation || s.verified) { "먼저 실기기 수신 검증을 완료해 주세요." }
        val backup = s.settings.backupMinutes?.let { AlarmMath.nextBackup(Instant.now(), LocalTime.of(it / 60, it % 60), ZoneId.systemDefault()) }
        app.coordinator.start(s.settings.targetMinutes, backup, validation, s.watch.nodeId)
    }
    fun cancel() = action { app.coordinator.cancel() }
    fun recover() = action { app.coordinator.restore(); refresh() }
    fun verify(sessionId: String, notes: String, confirmed: Boolean) = action {
        FieldValidation(app.store, app.settings).save(sessionId, state.value.watch, notes, confirmed)
        device.update { it.copy(message = "실기기 검증을 기록했습니다. 진행 중인 검증 세션을 끝내면 자동 알람을 시작할 수 있습니다.") }
    }
    fun record(notes: String) = action { require(notes.isNotBlank()); app.coordinator.record("FIELD_NOTE", notes) }
    fun clearMessage() { device.update { it.copy(message = null) } }
    private fun action(block: suspend () -> Unit) { viewModelScope.launch {
        device.update { it.copy(busy = true, message = null) }
        try { block() } catch (e: Exception) { device.update { it.copy(message = e.message ?: "작업을 완료하지 못했습니다.") } }
        finally { device.update { it.copy(busy = false) } }
    } }
}
