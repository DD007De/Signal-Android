package org.thoughtcrime.securesms.wear

import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import org.signal.core.util.wear.WearBridgeProtocol

/**
 * Shared phone-side node resolution for the Wear bridge push/wipe paths. Prefers nodes advertising
 * [WearBridgeProtocol.CAPABILITY]; falls back to all connected nodes when the capability hasn't
 * propagated (some GmsCore builds / Samsung watches never surface it). Bridge paths are
 * app-specific, so a node without the companion simply ignores them. Mirrors the watch-side
 * `WearDataClient.send()` fallback.
 */
object WearNodes {
  fun reachableOrConnected(context: Context): List<String> {
    val capable = Tasks.await(
      Wearable.getCapabilityClient(context).getCapability(WearBridgeProtocol.CAPABILITY, CapabilityClient.FILTER_REACHABLE)
    ).nodes.map { it.id }
    if (capable.isNotEmpty()) return capable
    return Tasks.await(Wearable.getNodeClient(context).connectedNodes).map { it.id }
  }
}
