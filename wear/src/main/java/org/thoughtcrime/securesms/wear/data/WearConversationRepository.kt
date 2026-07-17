package org.thoughtcrime.securesms.wear.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import org.signal.core.util.wear.ConversationDto
import org.signal.core.util.wear.MessageDto
import org.signal.core.util.wear.MessagesPayload
import org.thoughtcrime.securesms.wear.bridge.WearDataClient
import org.thoughtcrime.securesms.wear.data.db.WearConversationDao
import org.thoughtcrime.securesms.wear.data.db.WearConversationEntity

/**
 * Watch-side facade over the Milestone 2 sync engine: the local Room cache of conversations (via
 * [dao]) and the outbound requests/reply to the paired phone (via [dataClient]). UI code should go
 * through this rather than touching [WearConversationDao] or [WearDataClient] directly.
 */
class WearConversationRepository(
  private val dao: WearConversationDao,
  private val dataClient: WearDataClient
) : WearConversationDataSource {

  /** The cached conversation list, newest first, mapped from Room entities back to the wire DTO. */
  override fun conversations(): Flow<List<ConversationDto>> = dao.observeAll().map { entities -> entities.map { it.toDto() } }

  /**
   * The most recently received messages payload for whichever thread was last opened via
   * [openThread]. Updated by [org.thoughtcrime.securesms.wear.bridge.WearMessageListenerService]
   * when a [org.signal.core.util.wear.WearBridgeProtocol.PATH_MESSAGES] push arrives from the
   * phone; null until the first payload arrives. Messages are not persisted to Room, unlike
   * conversations.
   */
  override val messages: StateFlow<MessagesPayload?> = WearMessagesSink.state

  /** Asks the paired phone to push a fresh conversation list; the result lands in [conversations] once received and cached. */
  override suspend fun refresh(): Boolean = dataClient.requestConversations()

  /** Asks the paired phone for [threadId]'s recent messages; the result lands in [messages] once received. */
  override suspend fun openThread(threadId: Long): Boolean = dataClient.requestMessages(threadId)

  /**
   * Sends a reply [body] for [threadId] to the paired phone. Optimistically appends it to
   * [WearMessagesSink] first so it shows up at the bottom of the open conversation instantly,
   * rather than waiting for the fire-and-forget send's round trip. The next authoritative
   * [org.signal.core.util.wear.WearBridgeProtocol.PATH_MESSAGES] push overwrites the sink
   * wholesale, reconciling the optimistic entry with the real one.
   */
  override suspend fun reply(threadId: Long, body: String): Boolean {
    WearMessagesSink.state.value = appendOptimisticReply(WearMessagesSink.state.value, threadId, body, System.currentTimeMillis())
    return dataClient.sendReply(threadId, body)
  }

  /** Tells the paired phone to mark [threadId] read. */
  override suspend fun markRead(threadId: Long) {
    dataClient.markRead(threadId)
  }

  /** Tells the paired phone to mute [threadId] indefinitely. */
  override suspend fun mute(threadId: Long) {
    dataClient.mute(threadId, muteUntil = Long.MAX_VALUE)
  }

  /** Tells the paired phone to unmute [threadId]. */
  override suspend fun unmute(threadId: Long) {
    dataClient.mute(threadId, muteUntil = 0L)
  }

  /** Wipes the local conversation cache, e.g. on logout/unpair. */
  suspend fun clearCache() = dao.clear()

  private fun WearConversationEntity.toDto(): ConversationDto = ConversationDto(
    threadId = threadId,
    title = title,
    lastBody = lastBody,
    timestamp = timestamp,
    unread = unread,
    avatarColor = avatarColor,
    initials = initials
  )
}

/**
 * Process-wide sink for incoming [MessagesPayload] pushes from the phone. There is no per-request
 * correlation id in the Milestone 2 wire format, so
 * [org.thoughtcrime.securesms.wear.bridge.WearMessageListenerService] writes the latest payload
 * here directly and [WearConversationRepository.messages] exposes it to the UI layer — mirroring
 * the Milestone 1 [org.thoughtcrime.securesms.wear.bridge.LastReply] pattern, kept process-wide
 * rather than per-repository-instance since the listener service has no reference to a specific
 * repository instance to update.
 */
object WearMessagesSink {
  val state = MutableStateFlow<MessagesPayload?>(null)
}

/** Appends an outgoing [MessageDto] to [current] iff it is the open thread's payload; else returns it unchanged. */
fun appendOptimisticReply(current: MessagesPayload?, threadId: Long, body: String, timestamp: Long): MessagesPayload? {
  if (current == null || current.threadId != threadId) return current
  // author is not rendered for outgoing bubbles (ConversationScreen keys off `outgoing`), so its value is cosmetic.
  return current.copy(messages = current.messages + MessageDto(author = "You", body = body, timestamp = timestamp, outgoing = true))
}
