package com.wakemeup.mobile

import android.app.Application
import androidx.lifecycle.*
import com.wakemeup.core.*
import com.wakemeup.mobile.data.*
import java.time.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class DeviceState(val connectedNodes: Set<String> = emptySet(), val exact: Boolean = false, val notifications: Boolean = false, val fullScreen: Boolean = false, val busy: Boolean = false, val message: String? = null, val permissionsChecked: Boolean = false)
data class HomeState(val settings: AppSettings = AppSettings(), val watch: WatchStatus = WatchStatus(), val session: SleepSession? = null, val logs: List<LogEntity> = emptyList(), val device: DeviceState = DeviceState(), val history: List<SleepSession> = emptyList(), val sleepEvents: List<LogEntity> = emptyList()) {
    val watchConnected get() = watch.nodeId in device.connectedNodes
    val canStart get() = device.exact && device.notifications && watchConnected && watch.supported && watch.permission && session?.isActive != true && !device.busy
}
class HomeViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as WakeMeUpApp
    private val device = MutableStateFlow(DeviceState())
    init { viewModelScope.launch { app.dataLayer.connectionState.collect { nodes -> device.update { it.copy(connectedNodes = nodes) } } } }
    val state = combine(app.settings.settings, app.settings.watch, app.database.sessions().history(), app.database.sessions().sleepEvents()) { settings, watch, history, events ->
        HomeState(settings = settings, watch = watch, session = history.firstOrNull()?.session, history = history.map { it.session }, sleepEvents = events)
    }.combine(app.database.sessions().logs()) { state, logs -> state.copy(logs = logs) }
        .combine(device) { state, device -> state.copy(device = device) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HomeState())
    fun refresh() = run {
        device.update { it.copy(exact = app.scheduler.exactAccess(), notifications = app.scheduler.notificationAccess(), fullScreen = app.scheduler.fullScreenAccess(), permissionsChecked = true) }
        viewModelScope.launch {
            try { device.update { it.copy(connectedNodes = app.dataLayer.connected()) }; app.dataLayer.refresh() }
            catch (e: Exception) { app.dataLayer.updateConnections(emptySet()); device.update { it.copy(connectedNodes = emptySet(), message = "워치 연결 확인: ${e.message}") } }
        }
    }
    fun save(minutes: Int, backup: Int?) = action { app.settings.save(minutes, backup) }
    fun start() = action {
        val s = state.value
        check(s.copy(device = s.device.copy(busy = false)).canStart) { "필수 권한과 워치 연결을 확인해 주세요." }
        val backup = s.settings.backupMinutes?.let { AlarmMath.nextBackup(Instant.now(), LocalTime.of(it / 60, it % 60), ZoneId.systemDefault()) }
        app.coordinator.start(s.settings.targetMinutes, backup, false, s.watch.nodeId)
    }
    fun cancel() = action { app.coordinator.cancel() }
    fun recover() = action { app.coordinator.restore(); refresh() }
    fun finishSetup() = action { app.settings.completeOnboarding() }
    fun clearMessage() { device.update { it.copy(message = null) } }
    private fun action(block: suspend () -> Unit) { viewModelScope.launch {
        device.update { it.copy(busy = true, message = null) }
        try { block() } catch (e: Exception) { device.update { it.copy(message = e.message ?: "작업을 완료하지 못했습니다.") } }
        finally { device.update { it.copy(busy = false) } }
    } }
}
