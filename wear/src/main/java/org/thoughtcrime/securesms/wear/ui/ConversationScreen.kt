package org.thoughtcrime.securesms.wear.ui

import android.app.Activity
import android.app.RemoteInput
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.wear.input.RemoteInputIntentHelper
import org.signal.core.util.wear.MessageDto
import org.signal.core.util.wear.MessagesPayload
import org.thoughtcrime.securesms.wear.R

private const val REMOTE_INPUT_KEY = "wear_reply"

/** Incoming-message bubble fill; Signal's theme doesn't define a dedicated dark chat-bubble color. */
private val IncomingBubbleColor = Color(0xFF2A2A2A)
private val BubbleCornerRadius = 14.dp
private val BubbleMaxWidthFraction = 0.8f

/**
 * A single thread's recent messages plus reply affordances: a reply chip (launches the platform's
 * remote-input activity via [RemoteInputIntentHelper], configured to accept keyboard/voice/emoji
 * input) and a short list of preset one-tap replies. Requests [threadId]'s messages from the
 * paired phone via [onOpen] on first composition (and whenever [threadId] changes, e.g. navigating
 * directly between two threads), and also tells the paired phone to mark [threadId] read via
 * [onMarkRead] at the same time — opening a thread on the watch implies the user has read it. The
 * actual message content comes from the caller's [payload] (backed by
 * [WearConversationViewModel.messages]) so this screen stays a plain, stateless Composable.
 *
 * [title] is the conversation's display name (resolved by the caller via
 * [resolveConversationTitle]), shown as the header instead of a generic label.
 *
 * Milestone 4 Task D (WEAR-004) redesign: messages render as chat bubbles (outgoing right-aligned
 * in the theme's primary color, incoming left-aligned in [IncomingBubbleColor] with the sender's
 * name as a small label), ordered oldest-to-newest top-to-bottom, and the list auto-scrolls to the
 * newest message on open so the latest message and the reply chips are visible without any manual
 * scrolling.
 *
 * [onMute]/[onUnmute] surface both mute and unmute actions rather than a single toggle: the watch
 * has no local record of a thread's mute state (the [org.signal.core.util.wear.ConversationDto]
 * wire DTO doesn't carry one in this increment), so there's nothing to toggle against.
 */
@Composable
fun ConversationScreen(
  threadId: Long,
  title: String,
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

  val replyLabel = stringResource(R.string.wear_reply)
  val presetReplies = stringArrayResource(R.array.wear_preset_replies)

  val voiceReplyLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
    if (result.resultCode == Activity.RESULT_OK) {
      val body = RemoteInput.getResultsFromIntent(result.data)?.getCharSequence(REMOTE_INPUT_KEY)?.toString()
      if (!body.isNullOrBlank()) {
        onReply(threadId, body)
      }
    }
  }

  // Oldest-to-newest, top-to-bottom: the phone sends newest-first (matching MessageTable's
  // DATE_RECEIVED DESC ordering), so this reverses it for chat-style display.
  val messages = payload?.takeIf { it.threadId == threadId }?.messages.orEmpty().sortedBy { it.timestamp }

  val listState = rememberScalingLazyListState()
  LaunchedEffect(messages.size) {
    if (messages.isNotEmpty()) {
      // Index 0 is the ListHeader; the last message is therefore at index `messages.size`. Scrolling
      // there (rather than only to the top) puts the newest message, and the reply chips right after
      // it, in view immediately on open — no manual scrolling needed to reply.
      listState.scrollToItem(messages.size)
    }
  }

  ScalingLazyColumn(modifier = modifier, state = listState) {
    item { ListHeader { Text(title) } }

    items(messages.withIndex().toList(), key = { (index, message) -> "$index-${message.timestamp}" }) { (_, message) ->
      MessageBubble(message)
    }

    item {
      Chip(
        onClick = {
          val remoteInputs = listOf(
            RemoteInput.Builder(REMOTE_INPUT_KEY)
              .setLabel(replyLabel)
              // Allows the platform remote-input UI to offer keyboard + voice + emoji input (like
              // WhatsApp on Wear OS), not just voice dictation.
              .setAllowFreeFormInput(true)
              .build()
          )
          val intent = RemoteInputIntentHelper.createActionRemoteInputIntent()
          RemoteInputIntentHelper.putRemoteInputsExtra(intent, remoteInputs)
          voiceReplyLauncher.launch(intent)
        },
        label = { Text(replyLabel) },
        colors = ChipDefaults.primaryChipColors(),
        modifier = Modifier.padding(vertical = 2.dp)
      )
    }

    items(presetReplies.toList()) { preset ->
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
        label = { Text(stringResource(R.string.wear_mute)) },
        colors = ChipDefaults.secondaryChipColors(),
        modifier = Modifier.padding(vertical = 2.dp)
      )
    }

    item {
      Chip(
        onClick = { onUnmute(threadId) },
        label = { Text(stringResource(R.string.wear_unmute)) },
        colors = ChipDefaults.secondaryChipColors(),
        modifier = Modifier.padding(vertical = 2.dp)
      )
    }
  }
}

/**
 * A single chat bubble: right-aligned/theme-primary/no-author-label for outgoing messages,
 * left-aligned/dark-surface/author-labeled for incoming ones. Wrapped in [BoxWithConstraints] so
 * the bubble's max width ([BubbleMaxWidthFraction] of the available width) is computed from the
 * actual screen size rather than a hardcoded dp value — round watch screens range roughly
 * 192dp-454dp wide. Shows the full body text; no truncation.
 */
@Composable
private fun MessageBubble(message: MessageDto) {
  val outgoing = message.outgoing
  val bubbleColor = if (outgoing) MaterialTheme.colors.primary else IncomingBubbleColor
  val textColor = if (outgoing) MaterialTheme.colors.onPrimary else Color.White

  BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
    val maxBubbleWidth = maxWidth * BubbleMaxWidthFraction

    Box(
      modifier = Modifier
        .fillMaxWidth()
        .padding(vertical = 4.dp),
      contentAlignment = if (outgoing) Alignment.CenterEnd else Alignment.CenterStart
    ) {
      Column(
        modifier = Modifier
          .widthIn(max = maxBubbleWidth)
          .clip(RoundedCornerShape(BubbleCornerRadius))
          .background(bubbleColor)
          .padding(horizontal = 8.dp, vertical = 6.dp)
      ) {
        if (!outgoing) {
          Text(
            text = message.author,
            color = MaterialTheme.colors.secondary,
            fontSize = 10.sp
          )
        }
        Text(text = message.body, color = textColor)
      }
    }
  }
}
