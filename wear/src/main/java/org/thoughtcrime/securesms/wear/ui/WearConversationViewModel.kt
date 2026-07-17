package org.thoughtcrime.securesms.wear.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.signal.core.util.wear.ConversationDto
import org.signal.core.util.wear.MessagesPayload
import org.thoughtcrime.securesms.wear.data.WearConversationDataSource

/**
 * Presentation-layer state holder for the Milestone 2 watch UI ([ConversationListScreen],
 * [ConversationScreen]): the conversation list ([conversations]) and whichever thread's messages
 * were last requested via [open] ([messages]), both backed by [repository]. Constructor-injected
 * with the [WearConversationDataSource] interface — rather than the concrete
 * [org.thoughtcrime.securesms.wear.data.WearConversationRepository] — so this class can be unit
 * tested against a fake without touching Room or the GmsCore Data Layer client (see
 * `WearConversationViewModelTest`).
 */
class WearConversationViewModel(private val repository: WearConversationDataSource) : ViewModel() {

  /**
   * The cached conversation list, newest first, kept alive for the lifetime of this ViewModel
   * ([SharingStarted.Eagerly]) so it reflects [repository]'s underlying cache even before any
   * Composable subscribes to it.
   */
  val conversations: StateFlow<List<ConversationDto>> = repository.conversations()
    .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

  /** The most recently received messages payload for whichever thread was last opened via [open]. */
  val messages: StateFlow<MessagesPayload?> = repository.messages

  /** Asks the paired phone to refresh the conversation list; see [WearConversationDataSource.refresh]. */
  fun refresh() {
    viewModelScope.launch { repository.refresh() }
  }

  /** Opens [threadId], asking the paired phone for its recent messages; see [WearConversationDataSource.openThread]. */
  fun open(threadId: Long) {
    viewModelScope.launch { repository.openThread(threadId) }
  }

  /** Sends [body] as a reply to [threadId]; see [WearConversationDataSource.reply]. */
  fun reply(threadId: Long, body: String) {
    viewModelScope.launch { repository.reply(threadId, body) }
  }

  /** Marks [threadId] read; see [WearConversationDataSource.markRead]. */
  fun markRead(threadId: Long) {
    viewModelScope.launch { repository.markRead(threadId) }
  }

  /** Mutes [threadId] indefinitely; see [WearConversationDataSource.mute]. */
  fun mute(threadId: Long) {
    viewModelScope.launch { repository.mute(threadId) }
  }

  /** Unmutes [threadId]; see [WearConversationDataSource.unmute]. */
  fun unmute(threadId: Long) {
    viewModelScope.launch { repository.unmute(threadId) }
  }
}
