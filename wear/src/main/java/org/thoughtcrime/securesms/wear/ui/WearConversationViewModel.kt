package org.thoughtcrime.securesms.wear.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
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

  /** The thread most recently opened via [open], if any; re-requested when [conversations] changes. */
  private var openThreadId: Long? = null

  init {
    // WEAR-005: a list push (e.g. an incoming message anywhere) can also touch the open thread. When
    // that happens, re-request the open thread's messages so it refreshes without a manual pull.
    // `drop(1)` skips the initial cached emission so `open()` itself doesn't trigger a double-fetch.
    // Re-fetching messages does not itself change `conversations`, so there is no feedback loop.
    viewModelScope.launch {
      conversations.drop(1).collect {
        openThreadId?.let { id -> repository.openThread(id) }
      }
    }
  }

  /** Asks the paired phone to refresh the conversation list; see [WearConversationDataSource.refresh]. */
  fun refresh() {
    viewModelScope.launch { repository.refresh() }
  }

  /** Opens [threadId], asking the paired phone for its recent messages; see [WearConversationDataSource.openThread]. */
  fun open(threadId: Long) {
    openThreadId = threadId
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

  /** Reports [threadId] as the thread currently open on the watch; see [WearConversationDataSource.reportVisibleThread]. */
  fun setVisibleThread(threadId: Long) {
    viewModelScope.launch { repository.reportVisibleThread(threadId) }
  }

  /** Reports that no thread is currently open on the watch; see [WearConversationDataSource.reportVisibleThread]. */
  fun clearVisibleThread() {
    viewModelScope.launch { repository.reportVisibleThread(-1L) }
  }
}
