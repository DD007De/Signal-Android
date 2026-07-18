/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.wear

import assertk.assertThat
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNotEmpty
import assertk.assertions.isTrue
import org.junit.Test

/**
 * Verifies [WearDemoData]'s fixed fixture: non-empty conversations, per-thread demo messages, and
 * that the thread ids used by [WearDemoData.conversations] line up with the ones
 * [WearDemoData.demoMessages]/[WearDemoData.isDemoThread] recognize.
 */
class WearDemoDataTest {

  @Test
  fun conversationsIsNonEmpty() {
    val payload = WearDemoData.conversations()

    assertThat(payload.conversations).isNotEmpty()
  }

  @Test
  fun everyDemoConversationThreadIdIsRecognizedAsADemoThread() {
    val payload = WearDemoData.conversations()

    for (conversation in payload.conversations) {
      assertThat(WearDemoData.isDemoThread(conversation.threadId)).isTrue()
    }
  }

  @Test
  fun demoMessagesReturnsMessagesForEveryDemoThread() {
    val payload = WearDemoData.conversations()

    for (conversation in payload.conversations) {
      val messages = WearDemoData.demoMessages(conversation.threadId)

      assertThat(messages.threadId).isEqualTo(conversation.threadId)
      assertThat(messages.messages).isNotEmpty()
    }
  }

  @Test
  fun demoThreadIdsAreStableAndConsistentBetweenConversationsAndMessages() {
    val conversationThreadIds = WearDemoData.conversations().conversations.map { it.threadId }
    val messageThreadIds = listOf(
      WearDemoData.demoMessages(conversationThreadIds[0]).threadId,
      WearDemoData.demoMessages(conversationThreadIds[1]).threadId,
      WearDemoData.demoMessages(conversationThreadIds[2]).threadId
    )

    assertThat(conversationThreadIds).containsExactlyInAnyOrder(*messageThreadIds.toTypedArray())
  }

  @Test
  fun demoMessagesForUnknownThreadIdIsEmpty() {
    val payload = WearDemoData.demoMessages(threadId = -1L)

    assertThat(payload.messages).isEmpty()
  }

  @Test
  fun demoMessagesIncludeAtLeastOneOutgoingMessage() {
    val payload = WearDemoData.conversations()

    for (conversation in payload.conversations) {
      val messages = WearDemoData.demoMessages(conversation.threadId).messages
      assertThat(messages.any { it.outgoing }).isTrue()
    }
  }
}
