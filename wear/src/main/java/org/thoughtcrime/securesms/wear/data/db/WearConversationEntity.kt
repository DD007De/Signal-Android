package org.thoughtcrime.securesms.wear.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A cached, watch-side snapshot of a single conversation, synced down from the paired phone.
 */
@Entity(tableName = "wear_conversation")
data class WearConversationEntity(
  @PrimaryKey
  val threadId: Long,
  val title: String,
  val lastBody: String,
  val timestamp: Long,
  val unread: Int,
  // Milestone 4 Task B: cached alongside the rest of the row so the fallback avatar (colored
  // circle + initials) can render offline, without a live round-trip to the phone. Defaulted so
  // existing call sites (tests) that predate these columns still compile; mirrors
  // ConversationDto's own defaults.
  val avatarColor: Int = 0,
  val initials: String = ""
)
