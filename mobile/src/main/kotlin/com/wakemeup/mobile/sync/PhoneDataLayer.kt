package com.wakemeup.mobile.sync

import android.content.Context
import com.google.android.gms.wearable.*
import com.wakemeup.core.*
import com.wakemeup.mobile.WakeMeUpApp
import java.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class PhoneDataLayer(private val context: Context) : WatchBridge {
    private val app get() = context.applicationContext as WakeMeUpApp
    private val sendMutex = Mutex()
    private val connections = MutableStateFlow<Set<String>>(emptySet())
    val connectionState = connections.asStateFlow()
    fun updateConnections(nodes: Set<String>) { connections.value = nodes }
    override suspend fun sync(session: SleepSession) = sendMutex.withLock {
        // Concurrent refresh must never publish an older active snapshot after cancellation.
        val current = app.store.latest() ?: session
        val request = PutDataMapRequest.create(Protocol.COMMAND).apply {
            dataMap.putInt("version", Protocol.VERSION); dataMap.putString("sessionId", current.sessionId)
            dataMap.putLong("monitorStartedAt", current.monitorStartedAt.toEpochMilli()); dataMap.putInt("targetMinutes", current.targetMinutes)
            dataMap.putBoolean("active", current.isActive && current.status != SessionStatus.RINGING)
            dataMap.putString("watchNodeId", current.watchNodeId); dataMap.putBoolean("validationOnly", current.validationOnly)
            dataMap.putLong("revision", app.settings.nextCommandRevision())
        }.asPutDataRequest().setUrgent()
        Wearable.getDataClient(context).putDataItem(request).await()
        Unit
    }
    suspend fun connected(): Set<String> = Wearable.getNodeClient(context).connectedNodes.await().map { it.id }.toSet().also { updateConnections(it) }
    suspend fun refresh() {
        val localId = Wearable.getNodeClient(context).localNode.await().id
        val buffer = Wearable.getDataClient(context).dataItems.await()
        try { buffer.forEach { item -> if (item.uri.host != localId) process(item.uri.path ?: "", item.uri.host ?: "", DataMapItem.fromDataItem(item).dataMap) } }
        finally { buffer.release() }
        app.store.latest()?.let { sync(it) }
    }
    suspend fun process(path: String, node: String, map: DataMap) {
        if (map.getInt("version") != Protocol.VERSION) return
        when {
            path == Protocol.STATUS -> {
                val latest = app.store.latest()
                if (latest?.isActive == true && latest.watchNodeId != node) return
                app.settings.updateWatch(node, map)
                app.coordinator.monitoring(map.getString("sessionId") ?: "", map.getBoolean("monitoring"), map.getString("error")?.takeIf { it.isNotBlank() })
            }
            path.startsWith(Protocol.SLEEP) -> {
                app.coordinator.receive(SleepEvent(requireNotNull(map.getString("sessionId")), Instant.ofEpochMilli(map.getLong("onsetAt")), Instant.ofEpochMilli(map.getLong("watchReceivedAt")), node))
            }
        }
    }
}
class PhoneWearListener : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        val app = application as WakeMeUpApp
        // Complete database writes before this bound service callback returns.
        runBlocking(Dispatchers.IO) {
            events.filter { it.type == DataEvent.TYPE_CHANGED }.forEach { event ->
                val item = event.dataItem
                try { app.dataLayer.process(item.uri.path ?: "", item.uri.host ?: "", DataMapItem.fromDataItem(item).dataMap) }
                catch (e: Exception) { app.coordinator.record("DATA_ERROR", e.message ?: "이벤트 형식 오류") }
            }
        }
    }
    override fun onPeerConnected(peer: Node) { RecoveryWorker.enqueue(this) }
    override fun onCapabilityChanged(capabilityInfo: CapabilityInfo) {
        (application as WakeMeUpApp).dataLayer.updateConnections(capabilityInfo.nodes.map { it.id }.toSet())
        if (capabilityInfo.nodes.isNotEmpty()) RecoveryWorker.enqueue(this)
    }
}
