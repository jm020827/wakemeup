package com.wakemeup.wear

import com.google.android.gms.wearable.*
import com.wakemeup.core.Protocol
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await

class WatchDataListener : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        runBlocking(Dispatchers.IO) {
            val localId = Wearable.getNodeClient(this@WatchDataListener).localNode.await().id
            events.filter { it.type == DataEvent.TYPE_CHANGED && it.dataItem.uri.path == Protocol.COMMAND }.forEach {
                val map = DataMapItem.fromDataItem(it.dataItem).dataMap
                if (map.getInt("version") == Protocol.VERSION && map.getString("watchNodeId") == localId) {
                    runCatching { (application as WatchApp).store.command(map) }
                    RegistrationWorker.enqueue(this@WatchDataListener)
                }
            }
        }
    }
    override fun onPeerConnected(peer: Node) {
        // DataClient retains its items across disconnections; resend the durable outbox.
        DeliveryWorker.enqueue(this)
        RegistrationWorker.enqueue(this)
    }
    override fun onCapabilityChanged(capabilityInfo: CapabilityInfo) {
        DeliveryWorker.enqueue(this)
        RegistrationWorker.enqueue(this)
    }
}
