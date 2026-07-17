package org.thoughtcrime.securesms.wear.ui

import android.app.Activity
import android.app.RemoteInput
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.wear.input.RemoteInputIntentHelper
import org.signal.core.util.wear.MessageDto
import org.signal.core.util.wear.MessagesPayload

private const val REMOTE_INPUT_KEY = "wear_reply"
private val PRESET_REPLIES = listOf("👍", "OK", "On my way", "Call you later")

/**
 * A single thread's recent messages plus reply affordances: a voice-input chip (launches the
 * platform's remote-input activity via [RemoteInputIntentHelper]) and a short list of preset
 * one-tap replies. Requests [threadId]'s messages from the paired phone via [onOpen] on first
 * composition (and whenever [threadId] changes, e.g. navigating directly between two threads),
 * and also tells the paired phone to mark [threadId] read via [onMarkRead] at the same time —
 * opening a thread on the watch implies the user has read it. The actual message content comes
 * from the caller's [payload] (backed by [WearConversationViewModel.messages]) so this screen
 * stays a plain, stateless Composable.
 *
 * [onMute]/[onUnmute] surface both mute and unmute actions rather than a single toggle: the watch
 * has no local record of a thread's mute state (the [org.signal.core.util.wear.ConversationDto]
 * wire DTO doesn't carry one in this increment), so there's nothing to toggle against.
 */
@Composable
fun ConversationScreen(
  threadId: Long,
  payload: MessagesPayload?,
  onOpen: (Long) -> Unit,
  onReply: (Long, String) -> Unit,
  onMarkRead: (Long) -> Unit,
  onMute: (Long) -> Unit,
  onUnmute: (Long) -> Unit,
  modifier: Modifier = Modifier
) {
  LaunchedEffect(threadId) {
    onOpen(threadId)
    onMarkRead(threadId)
  }

  val voiceReplyLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
    if (result.resultCode == Activity.RESULT_OK) {
      val body = RemoteInput.getResultsFromIntent(result.data)?.getCharSequence(REMOTE_INPUT_KEY)?.toString()
      if (!body.isNullOrBlank()) {
        onReply(threadId, body)
      }
    }
  }

  val messages = payload?.takeIf { it.threadId == threadId }?.messages.orEmpty()

  ScalingLazyColumn(modifier = modifier) {
    item { ListHeader { Text("Conversation") } }

    items(messages.withIndex().toList(), key = { (index, message) -> "$index-${message.timestamp}" }) { (_, message) ->
      MessageRow(message)
    }

    item {
      Chip(
        onClick = {
          val remoteInputs = listOf(RemoteInput.Builder(REMOTE_INPUT_KEY).setLabel("Reply").build())
          val intent = RemoteInputIntentHelper.createActionRemoteInputIntent()
          RemoteInputIntentHelper.putRemoteInputsExtra(intent, remoteInputs)
          voiceReplyLauncher.launch(intent)
        },
        label = { Text("Voice reply") },
        colors = ChipDefaults.primaryChipColors(),
        modifier = Modifier.padding(vertical = 2.dp)
      )
    }

    items(PRESET_REPLIES) { preset ->
      Chip(
        onClick = { onReply(threadId, preset) },
        label = { Text(preset) },
        colors = ChipDefaults.secondaryChipColors(),
        modifier = Modifier.padding(vertical = 2.dp)
      )
    }

    item {
      Chip(
        onClick = { onMute(threadId) },
        label = { Text("Mute") },
        colors = ChipDefaults.secondaryChipColors(),
        modifier = Modifier.padding(vertical = 2.dp)
      )
    }

    item {
      Chip(
        onClick = { onUnmute(threadId) },
        label = { Text("Unmute") },
        colors = ChipDefaults.secondaryChipColors(),
        modifier = Modifier.padding(vertical = 2.dp)
      )
    }
  }
}

@Composable
private fun MessageRow(message: MessageDto) {
  val prefix = if (message.outgoing) "Me" else message.author
  Text(text = "$prefix: ${message.body}", maxLines = 3, overflow = TextOverflow.Ellipsis)
}
