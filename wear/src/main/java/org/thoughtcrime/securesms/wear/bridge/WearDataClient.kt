package org.thoughtcrime.securesms.wear.bridge

import android.content.Context
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import org.signal.core.util.logging.Log

/**
 * Watch-side client for the Data Layer bridge. Milestone 1 only sends a ping to the paired phone.
 */
class WearDataClient(private val context: Context) {

  /**
   * Sends a ping to the first reachable node that advertises the bridge capability.
   *
   * @return true if a target node was found and the message was handed to the Data Layer; false if
   *   no node is reachable or the Data Layer call fails (e.g. GmsCore unavailable).
   */
  suspend fun ping(): Boolean {
    return try {
      val capabilityInfo = Wearable.getCapabilityClient(context)
        .getCapability(WearBridgeProtocol.CAPABILITY, CapabilityClient.FILTER_REACHABLE)
        .await()

      val nodeId = capabilityInfo.nodes.firstOrNull()?.id ?: return false

      Wearable.getMessageClient(context)
        .sendMessage(nodeId, WearBridgeProtocol.PATH_PING, ByteArray(0))
        .await()

      true
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.w(TAG, "ping failed", e)
      false
    }
  }

  companion object {
    private val TAG = Log.tag(WearDataClient::class.java)
  }
}
