package org.thoughtcrime.securesms.wear

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import org.signal.core.util.concurrent.SignalExecutors
import org.signal.core.util.logging.Log
import org.signal.core.util.wear.ConversationDto
import org.signal.core.util.wear.NotifyDto
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
        val nodeIds = reachableOrConnectedNodeIds(context)
        val threadIds = pushToReachableNodes(context, nodeIds, realResponder(context))
        if (nodeIds.isNotEmpty()) {
          // Milestone 4 Task C: real contact photos, over the Asset API rather than embedded in the
          // MessageClient conversations payload above. See WearAvatarPublisher's KDoc; like the rest
          // of this method, not exercised by JVM unit tests since it needs a real GmsCore DataClient.
          WearAvatarPublisher.publishAvatars(context, threadIds)
        }
      } catch (e: Exception) {
        Log.w(TAG, "Failed to push conversation update to watch", e)
      }
    }
  }

  /**
   * WEAR-005: push a per-message notification to the watch for [threadIds] — the threads the phone
   * itself just alerted on ([org.thoughtcrime.securesms.notifications.v2.NotificationFactory.notify]'s
   * return value). Runs on [SignalExecutors.BOUNDED], never throws back into the caller.
   */
  fun pushMessageNotifications(context: Context, threadIds: List<Long>) {
    if (threadIds.isEmpty()) return
    SignalExecutors.BOUNDED.execute {
      try {
        val nodeIds = reachableOrConnectedNodeIds(context)
        if (nodeIds.isEmpty()) return@execute
        val conversations = WearBridgeRepository(context).recentConversations().conversations
        pushNotificationsToNodes(conversations, nodeIds, threadIds, realResponder(context))
      } catch (e: Exception) {
        Log.w(TAG, "Failed to push message notification to watch", e)
      }
    }
  }

  /**
   * Reachable nodes advertising the bridge capability, falling back to all connected nodes when the
   * capability hasn't propagated (some GmsCore builds / Samsung watches never surface it). Bridge
   * paths are app-specific, so a node without the companion simply ignores them. Mirrors the
   * watch-side fallback in `WearDataClient.send()`.
   */
  private fun reachableOrConnectedNodeIds(context: Context): List<String> {
    val capable = Tasks.await(
      Wearable.getCapabilityClient(context)
        .getCapability(WearBridgeProtocol.CAPABILITY, CapabilityClient.FILTER_REACHABLE)
    ).nodes.map { it.id }
    if (capable.isNotEmpty()) return capable
    return Tasks.await(Wearable.getNodeClient(context).connectedNodes).map { it.id }
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
   *
   * @return the thread ids of the pushed conversations (empty if [nodeIds] was empty), so
   *   [onNotificationRefreshed] can hand them to [WearAvatarPublisher.publishAvatars] without
   *   re-querying [WearBridgeRepository].
   */
  @VisibleForTesting
  fun pushToReachableNodes(context: Context, nodeIds: List<String>, responder: WearBridgeListenerService.WearResponder): List<Long> {
    if (nodeIds.isEmpty()) {
      return emptyList()
    }

    val payload = WearBridgeRepository(context).recentConversations()
    val bytes = WearBridgeProtocol.encode(payload)

    nodeIds.forEach { nodeId ->
      responder.send(nodeId, WearBridgeProtocol.PATH_CONVERSATIONS, bytes)
    }

    return payload.conversations.map { it.threadId }
  }

  /**
   * WEAR-005: testable core of [pushMessageNotifications]. Given the already-read [conversations]
   * list, sends a [NotifyDto] on [WearBridgeProtocol.PATH_NOTIFY] to every node in [nodeIds] for
   * every thread in [threadIds] that has a matching conversation. Free of [Context]/DB so it unit
   * tests without GmsCore, exactly like [pushToReachableNodes].
   *
   * A no-op when [nodeIds] or [threadIds] is empty.
   */
  @VisibleForTesting
  fun pushNotificationsToNodes(
    conversations: List<ConversationDto>,
    nodeIds: List<String>,
    threadIds: List<Long>,
    responder: WearBridgeListenerService.WearResponder
  ) {
    if (nodeIds.isEmpty() || threadIds.isEmpty()) return

    val byThread = conversations.associateBy { it.threadId }
    threadIds.forEach { threadId ->
      val convo = byThread[threadId] ?: return@forEach
      val bytes = WearBridgeProtocol.encode(
        NotifyDto(threadId = threadId, title = convo.title, body = convo.lastBody, timestamp = convo.timestamp)
      )
      nodeIds.forEach { nodeId -> responder.send(nodeId, WearBridgeProtocol.PATH_NOTIFY, bytes) }
    }
  }
}
