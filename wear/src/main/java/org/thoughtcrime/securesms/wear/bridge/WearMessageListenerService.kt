package org.thoughtcrime.securesms.wear.bridge

import androidx.compose.runtime.mutableStateOf
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.signal.core.util.logging.Log
import org.signal.core.util.wear.ConversationDto
import org.signal.core.util.wear.ConversationsPayload
import org.signal.core.util.wear.MessagesPayload
import org.signal.core.util.wear.WearBridgeProtocol
import org.thoughtcrime.securesms.wear.data.WearMessagesSink
import org.thoughtcrime.securesms.wear.data.db.WearCacheDatabase
import org.thoughtcrime.securesms.wear.data.db.WearConversationDao
import org.thoughtcrime.securesms.wear.data.db.WearConversationEntity

/**
 * Watch-side receiver. Milestone 1 (WEAR-001) only handled the pong reply from the phone and
 * surfaced it through [LastReply]; that handler is kept below as a transport smoke test.
 * Milestone 2 (WEAR-002) adds the real push paths: a fresh conversation list is synced into the
 * local Room cache, and a thread's messages are forwarded to [WearMessagesSink] for the UI to
 * observe (messages are not persisted, only conversations are).
 */
class WearMessageListenerService : WearableListenerService() {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  override fun onMessageReceived(event: MessageEvent) {
    if (event.path == WearBridgeProtocol.PATH_PONG) {
      LastReply.state.value = "pong @ ${event.sourceNodeId.take(4)}"
      return
    }

    val dao = WearCacheDatabase.getInstance(applicationContext).wearConversationDao()
    scope.launch {
      try {
        handleIncoming(
          path = event.path,
          data = event.data,
          dao = dao,
          onMessages = { WearMessagesSink.state.value = it }
        )
      } catch (e: Exception) {
        Log.w(TAG, "Failed to handle ${event.path} from ${event.sourceNodeId}", e)
      }
    }
  }

  override fun onDestroy() {
    super.onDestroy()
    scope.cancel()
  }

  companion object {
    private val TAG = Log.tag(WearMessageListenerService::class.java)

    /**
     * Handles an incoming Milestone 2 push from the phone. Extracted from [onMessageReceived] so it
     * can be unit tested against an in-memory Room [WearConversationDao] without a real
     * [WearableListenerService] or GmsCore.
     *
     * - [WearBridgeProtocol.PATH_CONVERSATIONS]: decodes a [ConversationsPayload] and replaces the
     *   cache wholesale via [WearConversationDao.replaceAll], so a thread dropped from the phone's
     *   payload disappears from the watch too.
     * - [WearBridgeProtocol.PATH_MESSAGES]: decodes a [MessagesPayload] and forwards it to
     *   [onMessages]; messages are never written to [dao].
     *
     * Any other path is ignored (the M1 pong is handled separately in [onMessageReceived], before
     * this is called).
     */
    suspend fun handleIncoming(
      path: String,
      data: ByteArray,
      dao: WearConversationDao,
      onMessages: (MessagesPayload) -> Unit
    ) {
      when (path) {
        WearBridgeProtocol.PATH_CONVERSATIONS -> {
          val payload = WearBridgeProtocol.decode<ConversationsPayload>(data)
          dao.replaceAll(payload.conversations.map { it.toEntity() })
        }

        WearBridgeProtocol.PATH_MESSAGES -> {
          onMessages(WearBridgeProtocol.decode<MessagesPayload>(data))
        }
      }
    }

    private fun ConversationDto.toEntity(): WearConversationEntity = WearConversationEntity(
      threadId = threadId,
      title = title,
      lastBody = lastBody,
      timestamp = timestamp,
      unread = unread
    )
  }
}

/**
 * Trivial process-wide sink so the Milestone 1 Activity can observe the reply without a real
 * repository/state layer. Replaced by proper state in Milestone 2.
 */
object LastReply {
  val state = mutableStateOf("idle")
}
