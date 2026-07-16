package org.thoughtcrime.securesms.wear

import android.content.Context
import org.signal.core.util.wear.ConversationDto
import org.signal.core.util.wear.ConversationsPayload
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.keyvalue.SignalStore

/**
 * Phone-side read path for the Wear bridge (Milestone 2).
 *
 * Reads recent conversations from the existing database and maps them to lightweight, text-only
 * DTOs. Honors the notification-content privacy setting: when the user has hidden contact or
 * message content from notifications, the corresponding field is blanked before it ever leaves the
 * phone.
 */
class WearBridgeRepository(private val context: Context) {

  /** Recent conversations, newest first, mapped to [ConversationDto] with privacy applied. */
  fun recentConversations(limit: Int = DEFAULT_LIMIT): ConversationsPayload {
    val privacy = SignalStore.settings.messageNotificationsPrivacy
    val conversations = mutableListOf<ConversationDto>()

    SignalDatabase.threads.getRecentConversationList(
      limit,
      false, // includeInactiveGroups
      false, // individualsOnly
      false, // groupsOnly
      true, // hideV1Groups
      true, // hideSms
      true // hideSelf
    ).use { cursor ->
      val reader = SignalDatabase.threads.readerFor(cursor)
      var record = reader.getNext()
      while (record != null) {
        conversations += ConversationDto(
          threadId = record.threadId,
          title = if (privacy.isDisplayContact) record.recipient.getDisplayName(context) else GENERIC_TITLE,
          lastBody = if (privacy.isDisplayMessage) record.body else "",
          timestamp = record.date,
          unread = record.unreadCount
        )
        record = reader.getNext()
      }
    }

    return ConversationsPayload(conversations = conversations)
  }

  companion object {
    private const val DEFAULT_LIMIT = 25
    private const val GENERIC_TITLE = "Signal"
  }
}
