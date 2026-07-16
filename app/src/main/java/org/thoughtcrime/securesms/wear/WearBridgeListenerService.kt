package org.thoughtcrime.securesms.wear

import android.content.Context
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import org.signal.core.util.concurrent.SignalExecutors
import org.signal.core.util.logging.Log
import org.signal.core.util.wear.WearBridgeProtocol

/**
 * Phone-side endpoint of the Wear bridge.
 *
 * Milestone 1 (WEAR-001) only answered a watch ping with a pong to prove the transport; that
 * handler is kept below as a transport smoke test. Milestone 2 (WEAR-002) adds the real
 * request/response bridge: the watch asks for recent conversations or the messages in one thread,
 * and this service reads them from the database off the binder thread (via
 * [SignalExecutors.BOUNDED], mirroring [org.thoughtcrime.securesms.notifications.RemoteReplyReceiver])
 * and pushes the encoded payload back.
 */
class WearBridgeListenerService : WearableListenerService() {
  override fun onMessageReceived(event: MessageEvent) {
    handleMessage(
      context = this,
      path = event.path,
      data = event.data,
      sourceNodeId = event.sourceNodeId,
      responder = realResponder(this)
    )
  }

  /** Seam for testing: swap in a fake to capture outgoing (nodeId, path, bytes) without real GmsCore. */
  fun interface WearResponder {
    fun send(nodeId: String, path: String, bytes: ByteArray)
  }

  companion object {
    private val TAG = Log.tag(WearBridgeListenerService::class.java)

    private fun realResponder(context: Context): WearResponder = WearResponder { nodeId, path, bytes ->
      Wearable.getMessageClient(context)
        .sendMessage(nodeId, path, bytes)
        .addOnFailureListener { Log.w(TAG, "Failed to send $path to $nodeId", it) }
    }

    /**
     * Handles an incoming Wear bridge message. Extracted from [onMessageReceived] so it can be
     * unit tested with a fake [WearResponder] and without a real [WearableListenerService].
     */
    fun handleMessage(context: Context, path: String, data: ByteArray, sourceNodeId: String, responder: WearResponder) {
      when (path) {
        WearBridgeProtocol.PATH_PING -> {
          responder.send(sourceNodeId, WearBridgeProtocol.PATH_PONG, ByteArray(0))
        }

        WearBridgeProtocol.PATH_REQUEST_CONVERSATIONS -> {
          SignalExecutors.BOUNDED.execute {
            try {
              val payload = WearBridgeRepository(context).recentConversations()
              responder.send(sourceNodeId, WearBridgeProtocol.PATH_CONVERSATIONS, WearBridgeProtocol.encode(payload))
            } catch (e: Exception) {
              Log.w(TAG, "Failed to handle $path from $sourceNodeId", e)
            }
          }
        }

        WearBridgeProtocol.PATH_REQUEST_MESSAGES -> {
          SignalExecutors.BOUNDED.execute {
            try {
              val threadId = data.decodeToString().toLongOrNull()
              if (threadId == null) {
                Log.w(TAG, "Received malformed threadId for $path from $sourceNodeId")
                return@execute
              }
              val payload = WearBridgeRepository(context).recentMessages(threadId)
              responder.send(sourceNodeId, WearBridgeProtocol.PATH_MESSAGES, WearBridgeProtocol.encode(payload))
            } catch (e: Exception) {
              Log.w(TAG, "Failed to handle $path from $sourceNodeId", e)
            }
          }
        }
      }
    }
  }
}
