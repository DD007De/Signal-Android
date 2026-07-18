package org.thoughtcrime.securesms.wear

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import org.signal.core.util.concurrent.SignalExecutors
import org.signal.core.util.logging.Log
import org.signal.core.util.wear.WearBridgeProtocol

/**
 * Phone-side push path of the Wear bridge (Milestone 2, WEAR-002 Task 4).
 *
 * [WearBridgeListenerService] answers the watch's on-demand pull ([WearBridgeProtocol.PATH_REQUEST_CONVERSATIONS]),
 * but the watch also wants to hear about new messages as they arrive without polling. This object
 * is the push counterpart: [DefaultMessageNotifier][org.thoughtcrime.securesms.notifications.v2.DefaultMessageNotifier]
 * calls [onNotificationRefreshed] every time it refreshes the phone's own notification state (i.e.
 * on every incoming-message driven notification pass), and this pushes the current conversation
 * list to any reachable watch unprompted.
 */
object WearPushNotifier {
  private val TAG = Log.tag(WearPushNotifier::class.java)

  /**
   * Entry point called from [org.thoughtcrime.securesms.notifications.v2.DefaultMessageNotifier].
   *
   * Runs entirely on [SignalExecutors.BOUNDED] — never blocks the caller — and never throws back
   * into it: any failure (no GmsCore, no reachable node, DB error, etc.) is logged via [Log.w] and
   * swallowed so a Wear bridge problem can never break notification delivery.
   */
  fun onNotificationRefreshed(context: Context) {
    SignalExecutors.BOUNDED.execute {
      try {
        pushToReachableNodes(context, reachableNodeIds(context), realResponder(context))
      } catch (e: Exception) {
        Log.w(TAG, "Failed to push conversation update to watch", e)
      }
    }
  }

  private fun reachableNodeIds(context: Context): List<String> {
    val capabilityInfo = Tasks.await(
      Wearable.getCapabilityClient(context)
        .getCapability(WearBridgeProtocol.CAPABILITY, CapabilityClient.FILTER_REACHABLE)
    )
    return capabilityInfo.nodes.map { it.id }
  }

  private fun realResponder(context: Context): WearBridgeListenerService.WearResponder = WearBridgeListenerService.WearResponder { nodeId, path, bytes ->
    Wearable.getMessageClient(context)
      .sendMessage(nodeId, path, bytes)
      .addOnFailureListener { Log.w(TAG, "Failed to send $path to $nodeId", it) }
  }

  /**
   * Testable core of the push: given a set of reachable node ids and a [WearBridgeListenerService.WearResponder]
   * seam, reads the current conversation list from [WearBridgeRepository] and sends it on
   * [WearBridgeProtocol.PATH_CONVERSATIONS] to each node.
   *
   * If [nodeIds] is empty this is a cheap no-op: it returns before touching the database.
   */
  @VisibleForTesting
  fun pushToReachableNodes(context: Context, nodeIds: List<String>, responder: WearBridgeListenerService.WearResponder) {
    if (nodeIds.isEmpty()) {
      return
    }

    val payload = WearBridgeRepository(context).recentConversations()
    val bytes = WearBridgeProtocol.encode(payload)

    nodeIds.forEach { nodeId ->
      responder.send(nodeId, WearBridgeProtocol.PATH_CONVERSATIONS, bytes)
    }
  }
}
