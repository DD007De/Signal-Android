package org.thoughtcrime.securesms.wear

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.annotation.VisibleForTesting
import androidx.core.app.RemoteInput
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import org.signal.core.util.concurrent.SignalExecutors
import org.signal.core.util.logging.Log
import org.signal.core.util.wear.MuteRequest
import org.signal.core.util.wear.ReplyRequest
import org.signal.core.util.wear.WearBridgeProtocol
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.notifications.MarkReadReceiver
import org.thoughtcrime.securesms.notifications.RemoteReplyReceiver
import org.thoughtcrime.securesms.notifications.ReplyMethod
import org.thoughtcrime.securesms.notifications.v2.DefaultMessageNotifier
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientId

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
     *
     * [dispatchReply] is a seam over the [PATH_SEND_REPLY][WearBridgeProtocol.PATH_SEND_REPLY]
     * branch's `sendBroadcast` call, defaulting to the real thing so tests can capture the
     * [RemoteReplyReceiver] [Intent] instead of dispatching a real broadcast.
     *
     * [publishAvatars] is the same kind of seam over the
     * [PATH_REQUEST_CONVERSATIONS][WearBridgeProtocol.PATH_REQUEST_CONVERSATIONS] branch's
     * [WearAvatarPublisher.publishAvatars] call — it defaults to the real (GmsCore-touching)
     * publisher, so tests that need a hermetic run can inject a no-op or capturing fake instead.
     */
    fun handleMessage(
      context: Context,
      path: String,
      data: ByteArray,
      sourceNodeId: String,
      responder: WearResponder,
      dispatchReply: (Intent) -> Unit = { context.sendBroadcast(it) },
      publishAvatars: (Context, List<Long>) -> Unit = { ctx, threadIds -> WearAvatarPublisher.publishAvatars(ctx, threadIds) }
    ) {
      when (path) {
        WearBridgeProtocol.PATH_PING -> {
          responder.send(sourceNodeId, WearBridgeProtocol.PATH_PONG, ByteArray(0))
        }

        WearBridgeProtocol.PATH_REQUEST_CONVERSATIONS -> {
          SignalExecutors.BOUNDED.execute {
            try {
              val payload = WearBridgeRepository(context).recentConversations()
              responder.send(sourceNodeId, WearBridgeProtocol.PATH_CONVERSATIONS, WearBridgeProtocol.encode(payload))
              publishAvatars(context, payload.conversations.map { it.threadId })
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

        WearBridgeProtocol.PATH_SEND_REPLY -> {
          SignalExecutors.BOUNDED.execute {
            try {
              handleSendReply(context, data, dispatchReply)
            } catch (e: Exception) {
              Log.w(TAG, "Failed to handle $path from $sourceNodeId", e)
            }
          }
        }

        WearBridgeProtocol.PATH_MARK_READ -> {
          SignalExecutors.BOUNDED.execute {
            try {
              handleMarkRead(context, data)
            } catch (e: Exception) {
              Log.w(TAG, "Failed to handle $path from $sourceNodeId", e)
            }
          }
        }

        WearBridgeProtocol.PATH_MUTE -> {
          SignalExecutors.BOUNDED.execute {
            try {
              handleMute(data)
            } catch (e: Exception) {
              Log.w(TAG, "Failed to handle $path from $sourceNodeId", e)
            }
          }
        }
      }
    }

    /**
     * Decodes a [ReplyRequest] and, if it names a real thread and carries a non-blank body,
     * dispatches it through the existing [RemoteReplyReceiver] pipeline (the same one the phone's
     * own notification "reply" action uses) rather than inventing a new send path.
     */
    private fun handleSendReply(context: Context, data: ByteArray, dispatchReply: (Intent) -> Unit) {
      val request = WearBridgeProtocol.decode<ReplyRequest>(data)
      if (request.body.isBlank()) {
        Log.w(TAG, "Received blank reply body for threadId ${request.threadId}")
        return
      }

      val recipientId = SignalDatabase.threads.getRecipientIdForThreadId(request.threadId)
      if (recipientId == null) {
        Log.w(TAG, "No recipient found for threadId ${request.threadId}")
        return
      }

      val replyMethod = ReplyMethod.forRecipient(Recipient.resolved(recipientId))
      dispatchReply(buildReplyIntent(context, recipientId, replyMethod, request.body))
    }

    /**
     * Decodes a thread ID and, if it names a real thread, marks it read through the same
     * [SignalDatabase.threads]`.setRead` -> [MarkReadReceiver.process] -> notifier-refresh pipeline
     * that [org.thoughtcrime.securesms.notifications.RemoteReplyReceiver] uses after sending a reply,
     * so a watch-originated mark-read is indistinguishable from any other read-state change.
     */
    private fun handleMarkRead(context: Context, data: ByteArray) {
      val threadId = data.decodeToString().toLongOrNull()
      if (threadId == null) {
        Log.w(TAG, "Received malformed threadId for ${WearBridgeProtocol.PATH_MARK_READ}")
        return
      }

      val markedMessages = SignalDatabase.threads.setRead(threadId)
      MarkReadReceiver.process(markedMessages)
      AppDependencies.messageNotifier.updateNotification(context)
    }

    /**
     * Decodes a [MuteRequest] and, if it names a real thread, mutes (or unmutes) that thread's
     * recipient until [MuteRequest.muteUntil].
     */
    private fun handleMute(data: ByteArray) {
      val request = WearBridgeProtocol.decode<MuteRequest>(data)
      val recipientId = SignalDatabase.threads.getRecipientIdForThreadId(request.threadId)
      if (recipientId == null) {
        Log.w(TAG, "No recipient found for threadId ${request.threadId}")
        return
      }

      SignalDatabase.recipients.setMuted(recipientId, request.muteUntil)
    }

    /**
     * Builds the same [RemoteReplyReceiver.REPLY_ACTION] [Intent] that
     * [org.thoughtcrime.securesms.notifications.v2.NotificationConversation.getRemoteReplyIntent]
     * builds for the notification-shade "reply" action, so a watch-originated reply is
     * indistinguishable from a phone-notification reply once it reaches [RemoteReplyReceiver].
     * Extracted from [handleSendReply] so the intent contents can be asserted without a real
     * broadcast.
     */
    @VisibleForTesting
    internal fun buildReplyIntent(context: Context, recipientId: RecipientId, replyMethod: ReplyMethod, body: String): Intent {
      val intent = Intent(context, RemoteReplyReceiver::class.java)
        .setAction(RemoteReplyReceiver.REPLY_ACTION)
        .putExtra(RemoteReplyReceiver.RECIPIENT_EXTRA, recipientId)
        .putExtra(RemoteReplyReceiver.REPLY_METHOD, replyMethod)
        .putExtra(RemoteReplyReceiver.EARLIEST_TIMESTAMP, System.currentTimeMillis())

      val results = Bundle().apply {
        putCharSequence(DefaultMessageNotifier.EXTRA_REMOTE_REPLY, body)
      }
      RemoteInput.addResultsToIntent(arrayOf(RemoteInput.Builder(DefaultMessageNotifier.EXTRA_REMOTE_REPLY).build()), intent, results)

      return intent
    }
  }
}
