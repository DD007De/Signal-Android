/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.wear

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotEqualTo
import assertk.assertions.isTrue
import io.mockk.every
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thoughtcrime.securesms.database.MessageType
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.database.ThreadTable
import org.thoughtcrime.securesms.mms.IncomingMessage
import org.thoughtcrime.securesms.mms.OutgoingMessage
import org.thoughtcrime.securesms.preferences.widgets.NotificationPrivacyPreference
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientId
import org.thoughtcrime.securesms.testutil.RecipientTestRule

/**
 * Verifies [WearBridgeRepository.recentMessages]: mapping of author/body/timestamp/outgoing,
 * privacy blanking of message bodies, and exclusion of system/update records.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class WearBridgeRepositoryTest {

  @get:Rule
  val recipients = RecipientTestRule()

  private lateinit var repository: WearBridgeRepository
  private lateinit var senderId: RecipientId
  private var threadId: Long = 0

  @Before
  fun setUp() {
    repository = WearBridgeRepository(ApplicationProvider.getApplicationContext())
    senderId = recipients.createRecipient("Sender Name")
    threadId = SignalDatabase.threads.getOrCreateThreadIdFor(senderId, false, ThreadTable.DistributionTypes.DEFAULT)

    setPrivacy("all")
  }

  @Test
  fun mapsIncomingAndOutgoingMessages() {
    insertIncoming(time = 1000, body = "hello from sender")
    insertOutgoing(time = 1001, body = "hello from me")

    val payload = repository.recentMessages(threadId)

    assertThat(payload.threadId).isEqualTo(threadId)
    assertThat(payload.messages).hasSize(2)

    // Newest first (DATE_RECEIVED DESC).
    val outgoing = payload.messages[0]
    assertThat(outgoing.author).isEqualTo("You")
    assertThat(outgoing.body).isEqualTo("hello from me")
    assertThat(outgoing.timestamp).isEqualTo(1001)
    assertThat(outgoing.outgoing).isTrue()

    val incoming = payload.messages[1]
    assertThat(incoming.author).isEqualTo("Sender Name")
    assertThat(incoming.body).isEqualTo("hello from sender")
    assertThat(incoming.timestamp).isEqualTo(1000)
    assertThat(incoming.outgoing).isFalse()
  }

  @Test
  fun blanksBodyWhenMessageContentPrivacyIsHidden() {
    setPrivacy("off")

    insertIncoming(time = 1000, body = "secret content")

    val payload = repository.recentMessages(threadId)

    assertThat(payload.messages).hasSize(1)
    assertThat(payload.messages[0].body).isEqualTo("")
  }

  @Test
  fun usesGenericAuthorWhenContactPrivacyIsHidden() {
    setPrivacy("off")

    insertIncoming(time = 1000, body = "secret content")
    insertOutgoing(time = 1001, body = "my reply")

    val payload = repository.recentMessages(threadId)

    assertThat(payload.messages).hasSize(2)

    // Outgoing messages are still labeled "You" even with contact privacy hidden.
    val outgoing = payload.messages[0]
    assertThat(outgoing.author).isEqualTo("You")
    assertThat(outgoing.body).isEqualTo("")

    // Incoming author is replaced with the generic label; body stays blanked too.
    val incoming = payload.messages[1]
    assertThat(incoming.author).isEqualTo("Signal")
    assertThat(incoming.body).isEqualTo("")
  }

  @Test
  fun excludesSystemUpdateRecords() {
    insertIncoming(time = 1000, body = "a real chat message")
    insertExpirationTimerUpdate(time = 1001)

    val payload = repository.recentMessages(threadId)

    assertThat(payload.messages).hasSize(1)
    assertThat(payload.messages[0].body).isEqualTo("a real chat message")
  }

  @Test
  fun returnsNoMessagesForEmptyThread() {
    val payload = repository.recentMessages(threadId)
    assertThat(payload.messages).isEmpty()
  }

  @Test
  fun mapsAvatarColorAndInitialsForRecentConversations() {
    insertIncoming(time = 1000, body = "hello from sender")

    val payload = repository.recentConversations()
    val conversation = payload.conversations.first { it.threadId == threadId }

    // "Sender Name" -> first grapheme of each of the two name parts (see NameUtil.getAbbreviation).
    assertThat(conversation.initials).isEqualTo("SN")
    assertThat(conversation.avatarColor).isNotEqualTo(0)
  }

  @Test
  fun usesGenericInitialsAndNeutralColorWhenContactPrivacyIsHidden() {
    setPrivacy("off")

    insertIncoming(time = 1000, body = "hello from sender")

    val payload = repository.recentConversations()
    val conversation = payload.conversations.first { it.threadId == threadId }

    // Title is already blanked to the generic "Signal" label; initials/color must be derived from
    // that generic label too, not from the real recipient, or a hidden contact would leak identity
    // via color/initials even though the title itself is hidden.
    assertThat(conversation.initials).isEqualTo("S")
    assertThat(conversation.avatarColor).isEqualTo(0xFF6E6E6E.toInt())
  }

  @Test
  fun returnsDemoConversationsWhenDatabaseHasNoRealConversations() {
    // setUp() creates a thread row via getOrCreateThreadIdFor, but no message was ever inserted
    // into it, so it doesn't meet ThreadTable's MEANINGFUL_MESSAGES bar and the real query below
    // returns nothing -- this debug test build (BuildConfig.DEBUG is true for
    // testPlayProdDebugUnitTest) should fall back to WearDemoData's fixed fixture.
    val payload = repository.recentConversations()

    assertThat(payload.conversations).isEqualTo(WearDemoData.conversations().conversations)
  }

  @Test
  fun returnsDemoMessagesWhenDemoThreadHasNoRealMessages() {
    val demoThreadId = WearDemoData.conversations().conversations.first().threadId

    val payload = repository.recentMessages(demoThreadId)

    assertThat(payload.messages).isEqualTo(WearDemoData.demoMessages(demoThreadId).messages)
  }

  // region helpers

  private fun setPrivacy(preference: String) {
    every { recipients.signalStore.settings.messageNotificationsPrivacy } returns NotificationPrivacyPreference(preference)
  }

  private fun insertIncoming(time: Long, body: String): Long {
    val message = IncomingMessage(
      type = MessageType.NORMAL,
      from = senderId,
      sentTimeMillis = time,
      serverTimeMillis = time,
      receivedTimeMillis = time,
      body = body
    )
    return SignalDatabase.messages.insertMessageInbox(message, threadId).get().messageId
  }

  private fun insertOutgoing(time: Long, body: String): Long {
    val message = OutgoingMessage.text(
      threadRecipient = Recipient.resolved(senderId),
      body = body,
      expiresIn = 0,
      sentTimeMillis = time
    )
    return SignalDatabase.messages.insertMessageOutbox(message, threadId).messageId
  }

  private fun insertExpirationTimerUpdate(time: Long): Long {
    val message = IncomingMessage(
      type = MessageType.EXPIRATION_UPDATE,
      from = senderId,
      sentTimeMillis = time,
      serverTimeMillis = time,
      receivedTimeMillis = time,
      expiresIn = 60_000
    )
    return SignalDatabase.messages.insertMessageInbox(message, threadId).get().messageId
  }

  // endregion
}
