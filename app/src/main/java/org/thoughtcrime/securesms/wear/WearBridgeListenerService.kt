package org.thoughtcrime.securesms.wear

import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService

/**
 * Phone-side endpoint of the Wear bridge.
 *
 * Milestone 1 (WEAR-001) only answers a watch ping with a pong to prove the transport. Milestone 2
 * replaces this with the real read/push/reply bridge that reads conversations from the database and
 * enqueues replies through the existing send pipeline.
 */
class WearBridgeListenerService : WearableListenerService() {
  override fun onMessageReceived(event: MessageEvent) {
    if (event.path == WearBridgeProtocol.PATH_PING) {
      Wearable.getMessageClient(this)
        .sendMessage(event.sourceNodeId, WearBridgeProtocol.PATH_PONG, ByteArray(0))
    }
  }
}
