package org.thoughtcrime.securesms.wear.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.Text
import org.signal.core.util.wear.ConversationDto

/**
 * Milestone 2 start destination: the synced conversation list, newest first. Requests a fresh
 * sync from the paired phone via [onRefresh] on first composition; the actual list content comes
 * from the caller's [conversations] (backed by [WearConversationViewModel.conversations]) so this
 * screen stays a plain, stateless Composable.
 */
@Composable
fun ConversationListScreen(
  conversations: List<ConversationDto>,
  onRefresh: () -> Unit,
  onOpen: (Long) -> Unit,
  modifier: Modifier = Modifier
) {
  LaunchedEffect(Unit) { onRefresh() }

  val sorted = conversations.sortedByDescending { it.timestamp }

  ScalingLazyColumn(modifier = modifier) {
    item { ListHeader { Text("Chats") } }

    if (sorted.isEmpty()) {
      item { Text("No conversations yet") }
    }

    items(sorted, key = { it.threadId }) { conversation ->
      Chip(
        onClick = { onOpen(conversation.threadId) },
        label = { Text(text = conversation.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        secondaryLabel = { Text(text = conversation.lastBody, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        icon = { ConversationAvatar(color = conversation.avatarColor, initials = conversation.initials) },
        colors = ChipDefaults.secondaryChipColors(),
        modifier = Modifier.padding(vertical = 2.dp)
      )
    }
  }
}
