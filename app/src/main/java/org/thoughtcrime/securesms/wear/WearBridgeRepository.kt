package org.thoughtcrime.securesms.wear

import android.content.Context
import org.signal.core.util.wear.ConversationDto
import org.signal.core.util.wear.ConversationsPayload
import org.signal.core.util.wear.MessageDto
import org.signal.core.util.wear.MessagesPayload
import org.thoughtcrime.securesms.database.MessageTable
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.keyvalue.SignalStore

/**
 * Phone-side read path for the Wear bridge (Milestone 2).
 *
 * Reads recent conversations from the existing database and maps them to lightweight, text-only
 * DTOs. Honors the notification-content privacy setting: when the user has hidden contact or
 * message content from notifications, the corresponding field is blanked before it ever leaves the
 * phone.
 *
 * ## Privacy posture (WEAR-002 Task 9)
 *
 * A summary of what's enforced where for the whole Wear bridge, gathered in one place since the
 * individual pieces live across `:core`, `:app`, and `:wear`:
 *
 * - **Hide-content ([SignalStore.settings.messageNotificationsPrivacy]):** enforced *here*, on the
 *   phone, before anything is encoded onto the wire — [recentConversations] blanks `title`/`lastBody`
 *   and [recentMessages] blanks `body` when the user has hidden contact/message content from
 *   notifications. The watch never receives the real text in the first place; there's nothing for
 *   it to have cached or to un-hide.
 * - **Update-record exclusion:** [recentMessages] drops system/update records (group changes,
 *   disappearing-timer changes, call events, etc. — [org.thoughtcrime.securesms.database.model.MessageRecord.isUpdate])
 *   before mapping, so synthesized system prose never gets sent as if it were a chat message.
 * - **Disappearing messages:** enforced implicitly, not by any dedicated code path. Disappearing
 *   messages are DB-managed on the phone — an expired row is gone from
 *   [org.thoughtcrime.securesms.database.MessageTable] before [recentMessages] ever reads it, so it
 *   can't be pushed to the watch. For the conversation list, [WearMessageListenerService][
 *   org.thoughtcrime.securesms.wear.bridge.WearMessageListenerService]'s
 *   [org.signal.core.util.wear.WearBridgeProtocol.PATH_CONVERSATIONS] handling always does a
 *   full-replace ([org.thoughtcrime.securesms.wear.data.db.WearConversationDao.replaceAll]) of the
 *   watch's cache, so once a disappeared message was the last message in a thread, the next push
 *   (triggered by [WearPushNotifier.onNotificationRefreshed] or the watch's own
 *   [org.signal.core.util.wear.WearBridgeProtocol.PATH_REQUEST_CONVERSATIONS] pull) drops its
 *   snippet from the watch too — there's no separate "watch, please forget this message" signal
 *   needed for that case.
 * - **Encrypted cache:** the watch's local mirror of this data
 *   ([org.thoughtcrime.securesms.wear.data.db.WearCacheDatabase]) is SQLCipher-encrypted at rest,
 *   keyed by a passphrase held in `EncryptedSharedPreferences`
 *   ([org.thoughtcrime.securesms.wear.data.db.WearCachePassphrase], `:wear` module) — a lost or
 *   stolen watch doesn't expose the cache in plaintext.
 * - **Wipe on logout:** [WearWipeNotifier.onLogout] pushes
 *   [org.signal.core.util.wear.WearBridgeProtocol.PATH_WIPE] to every reachable watch when this
 *   phone's account is deleted/deregistered, which [WearMessageListenerService][
 *   org.thoughtcrime.securesms.wear.bridge.WearMessageListenerService] handles by clearing the
 *   cache wholesale. See [WearWipeNotifier]'s KDoc for exactly which call site triggers this and
 *   which related "clear all data" call sites were deliberately left unwired pending a follow-up
 *   decision.
 * - **Wipe on unpair:** independent of the phone entirely —
 *   [org.thoughtcrime.securesms.wear.bridge.WearMessageListenerService.onCapabilityChanged] clears
 *   the watch's own cache the moment no phone advertising
 *   [org.signal.core.util.wear.WearBridgeProtocol.CAPABILITY] is reachable any more (unpaired /
 *   uninstalled), covering the case where the phone never gets a chance to send `PATH_WIPE` at all.
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

  /**
   * Recent messages in a single thread, mapped to [MessageDto] with privacy applied. Ordered
   * newest first, matching [MessageTable.getConversation]'s default `DATE_RECEIVED DESC` ordering
   * (the reader is used as-is, unreversed).
   *
   * System/update records (group changes, disappearing-timer changes, call events, etc. — anything
   * where [MessageRecord.isUpdate] is true) are excluded: their [MessageRecord.getDisplayBody] is
   * synthesized system prose, not a chat message, and shouldn't be shown on the watch as one.
   */
  fun recentMessages(threadId: Long, limit: Int = DEFAULT_LIMIT): MessagesPayload {
    val privacy = SignalStore.settings.messageNotificationsPrivacy
    val messages = mutableListOf<MessageDto>()

    MessageTable.mmsReaderFor(SignalDatabase.messages.getConversation(threadId, 0, limit.toLong())).use { reader ->
      var record = reader.getNext()
      while (record != null) {
        if (!record.isUpdate) {
          messages += MessageDto(
            author = if (record.isOutgoing) SELF_AUTHOR else record.fromRecipient.getDisplayName(context),
            body = if (privacy.isDisplayMessage) record.getDisplayBody(context).toString() else "",
            timestamp = record.timestamp,
            outgoing = record.isOutgoing
          )
        }
        record = reader.getNext()
      }
    }

    return MessagesPayload(threadId = threadId, messages = messages)
  }

  companion object {
    private const val DEFAULT_LIMIT = 25
    private const val GENERIC_TITLE = "Signal"
    private const val SELF_AUTHOR = "You"
  }
}
