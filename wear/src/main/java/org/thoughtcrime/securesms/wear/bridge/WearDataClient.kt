package org.thoughtcrime.securesms.wear.bridge

import android.content.Context
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await

/**
 * Watch-side client for the Data Layer bridge. Milestone 1 only sends a ping to the paired phone.
 */
class WearDataClient(private val context: Context) {

  /**
   * Sends a ping to the first reachable node that advertises the bridge capability.
   *
   * @return true if a target node was found and the message was handed to the Data Layer.
   */
  suspend fun ping(): Boolean {
    val capabilityInfo = Wearable.getCapabilityClient(context)
      .getCapability(WearBridgeProtocol.CAPABILITY, CapabilityClient.FILTER_REACHABLE)
      .await()

    val nodeId = capabilityInfo.nodes.firstOrNull()?.id ?: return false

    Wearable.getMessageClient(context)
      .sendMessage(nodeId, WearBridgeProtocol.PATH_PING, ByteArray(0))
      .await()

    return true
  }
}
