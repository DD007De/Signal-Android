package org.thoughtcrime.securesms.wear.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text

/**
 * Milestone 4 Task B: the watch-side rendering of the fallback avatar the phone computes in
 * [org.signal.core.util.wear.ConversationDto.avatarColor]/[org.signal.core.util.wear.ConversationDto.initials]
 * (Milestone 4 Task A) — a Signal-style colored circle with up to two initials, matching the
 * phone's own fallback avatar look without ever sending photo bytes over the Data Layer's
 * ~100KB message limit.
 *
 * [color] is an ARGB color int (as produced by the phone's `AvatarColor.colorInt()`), fed
 * straight into [Color]. Blank [initials] (e.g. a hidden/privacy-redacted contact, see
 * `WearBridgeRepository`) renders as a plain colored circle with no text.
 */
@Composable
fun ConversationAvatar(
  color: Int,
  initials: String,
  modifier: Modifier = Modifier
) {
  Box(
    modifier = modifier
      .size(AVATAR_SIZE)
      .clip(CircleShape)
      .background(Color(color)),
    contentAlignment = Alignment.Center
  ) {
    if (initials.isNotBlank()) {
      Text(
        text = initials,
        color = Color.White,
        fontSize = 14.sp
      )
    }
  }
}

private val AVATAR_SIZE = 36.dp
