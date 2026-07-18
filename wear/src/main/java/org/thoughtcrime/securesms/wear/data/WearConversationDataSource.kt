package org.thoughtcrime.securesms.wear.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import org.signal.core.util.wear.ConversationDto
import org.signal.core.util.wear.MessagesPayload

/**
 * Minimal surface the Milestone 2 UI layer (`org.thoughtcrime.securesms.wear.ui`) needs from the
 * sync engine. Extracted from [WearConversationRepository] so
 * [org.thoughtcrime.securesms.wear.ui.WearConversationViewModel] can be constructor-injected and
 * unit tested against a fake, without pulling in [WearConversationRepository]'s Room/GmsCore
 * dependencies.
 */
interface WearConversationDataSource {
  /** The cached conversation list, newest first. */
  fun conversations(): Flow<List<ConversationDto>>

  /** The most recently received messages payload for whichever thread was last opened via [openThread]. */
  val messages: StateFlow<MessagesPayload?>

  /** Asks the paired phone to push a fresh conversation list; the result lands in [conversations] once received. */
  suspend fun refresh(): Boolean

  /** Asks the paired phone for [threadId]'s recent messages; the result lands in [messages] once received. */
  suspend fun openThread(threadId: Long): Boolean

  /** Sends a reply [body] for [threadId] to the paired phone. */
  suspend fun reply(threadId: Long, body: String): Boolean
}
