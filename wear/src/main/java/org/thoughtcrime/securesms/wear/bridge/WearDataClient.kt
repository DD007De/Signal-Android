package org.thoughtcrime.securesms.wear.bridge

import android.content.Context
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import org.signal.core.util.logging.Log
import org.signal.core.util.wear.MuteRequest
import org.signal.core.util.wear.ReplyRequest
import org.signal.core.util.wear.WearBridgeProtocol

/**
 * Watch-side client for the Data Layer bridge. Milestone 1 only sent a ping to the paired phone;
 * Milestone 2 adds the watch -> phone request/push paths: asking for the conversation list, asking
 * for a thread's recent messages, and sending a reply. Responses/pushes arrive asynchronously and
 * are handled by [WearMessageListenerService], not returned from these calls.
 *
 * GmsCore-dependent send paths (this whole class) are not covered by JVM unit tests, consistent
 * with the Milestone 1 precedent set by this same class's original `ping()`; they're exercised
 * on-device instead. The receive side ([WearMessageListenerService.handleIncoming]) is unit
 * tested, since it doesn't touch GmsCore directly.
 */
class WearDataClient(private val context: Context) {

  /**
   * Sends a ping to the first reachable node that advertises the bridge capability.
   *
   * @return true if a target node was found and the message was handed to the Data Layer; false if
   *   no node is reachable or the Data Layer call fails (e.g. GmsCore unavailable).
   */
  suspend fun ping(): Boolean = send(WearBridgeProtocol.PATH_PING, ByteArray(0))

  /**
   * Asks the paired phone for the current conversation list. The response is pushed back
   * asynchronously on [WearBridgeProtocol.PATH_CONVERSATIONS] and handled by
   * [WearMessageListenerService].
   *
   * @return true if a target node was found and the request was handed to the Data Layer; false
   *   otherwise (see [ping]).
   */
  suspend fun requestConversations(): Boolean = send(WearBridgeProtocol.PATH_REQUEST_CONVERSATIONS, ByteArray(0))

  /**
   * Asks the paired phone for recent messages in [threadId]. The response is pushed back
   * asynchronously on [WearBridgeProtocol.PATH_MESSAGES] and handled by
   * [WearMessageListenerService].
   *
   * @return true if a target node was found and the request was handed to the Data Layer; false
   *   otherwise (see [ping]).
   */
  suspend fun requestMessages(threadId: Long): Boolean = send(WearBridgeProtocol.PATH_REQUEST_MESSAGES, threadId.toString().encodeToByteArray())

  /**
   * Sends a reply [body] for [threadId] to the paired phone, to be dispatched through its existing
   * reply pipeline.
   *
   * @return true if a target node was found and the reply was handed to the Data Layer; false
   *   otherwise (see [ping]).
   */
  suspend fun sendReply(threadId: Long, body: String): Boolean {
    return send(WearBridgeProtocol.PATH_SEND_REPLY, WearBridgeProtocol.encode(ReplyRequest(threadId = threadId, body = body)))
  }

  /**
   * Tells the paired phone to mark [threadId] read.
   *
   * @return true if a target node was found and the request was handed to the Data Layer; false
   *   otherwise (see [ping]).
   */
  suspend fun markRead(threadId: Long): Boolean = send(WearBridgeProtocol.PATH_MARK_READ, threadId.toString().encodeToByteArray())

  /**
   * Tells the paired phone to mute or unmute [threadId] until [muteUntil] (epoch millis; `0L` to
   * unmute).
   *
   * @return true if a target node was found and the request was handed to the Data Layer; false
   *   otherwise (see [ping]).
   */
  suspend fun mute(threadId: Long, muteUntil: Long): Boolean {
    return send(WearBridgeProtocol.PATH_MUTE, WearBridgeProtocol.encode(MuteRequest(threadId = threadId, muteUntil = muteUntil)))
  }

  /** Tells the phone which thread the watch currently has open (or -1 for none), so it can skip a redundant notification. */
  suspend fun reportVisibleThread(threadId: Long): Boolean = send(WearBridgeProtocol.PATH_VISIBLE_THREAD, threadId.toString().encodeToByteArray())

  /**
   * Finds the first reachable node advertising [WearBridgeProtocol.CAPABILITY] and sends [data] on
   * [path] to it. Shared, crash-safe send path for [ping], [requestConversations],
   * [requestMessages], [sendReply], [markRead], [mute], and [reportVisibleThread].
   *
   * @return true if a target node was found and the message was handed to the Data Layer; false if
   *   no node is reachable or the Data Layer call fails (e.g. GmsCore unavailable).
   */
  private suspend fun send(path: String, data: ByteArray): Boolean {
    return try {
      val capabilityNodes = Wearable.getCapabilityClient(context)
        .getCapability(WearBridgeProtocol.CAPABILITY, CapabilityClient.FILTER_REACHABLE)
        .await()
        .nodes
        .map { it.id }

      // Capability announcements don't always propagate phone <-> watch on every GmsCore build
      // (notably some Samsung watches), so fall back to all connected nodes. Our message paths are
      // bridge-specific, so a node without our companion app simply ignores them.
      val targets = capabilityNodes.ifEmpty {
        Wearable.getNodeClient(context).connectedNodes.await().map { it.id }
      }

      if (targets.isEmpty()) {
        Log.w(TAG, "$path: no reachable node")
        return false
      }

      targets.forEach { nodeId ->
        Wearable.getMessageClient(context).sendMessage(nodeId, path, data).await()
      }

      true
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.w(TAG, "$path failed", e)
      false
    }
  }

  companion object {
    private val TAG = Log.tag(WearDataClient::class.java)
  }
}
