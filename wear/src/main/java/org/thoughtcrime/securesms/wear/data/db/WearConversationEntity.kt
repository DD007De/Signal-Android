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
  val unread: Int
)
