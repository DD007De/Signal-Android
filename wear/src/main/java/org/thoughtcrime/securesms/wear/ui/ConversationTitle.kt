package org.thoughtcrime.securesms.wear.ui

import org.signal.core.util.wear.ConversationDto

/**
 * Milestone 4 Task D (WEAR-004): resolves [threadId]'s display title for [ConversationScreen]'s
 * header from the watch's cached conversation list ([WearConversationViewModel.conversations]),
 * so the header reads the actual contact/group name instead of a generic hardcoded string.
 *
 * Falls back to [fallback] (a localized "Conversation"/"Gesprek" string supplied by the caller via
 * [androidx.compose.ui.res.stringResource]) when [threadId] isn't present in [conversations] yet —
 * e.g. the watch deep-links into a thread before its first list sync completes.
 *
 * Kept as a plain, non-Composable function (rather than inlined into [WearMainActivity]'s
 * Composable body) so it's unit-testable without Robolectric/Compose test infrastructure.
 */
fun resolveConversationTitle(
  conversations: List<ConversationDto>,
  threadId: Long,
  fallback: String
): String {
  return conversations.firstOrNull { it.threadId == threadId }?.title ?: fallback
}
