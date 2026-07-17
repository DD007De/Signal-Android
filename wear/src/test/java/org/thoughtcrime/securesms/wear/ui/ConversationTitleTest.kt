package org.thoughtcrime.securesms.wear.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import org.signal.core.util.wear.ConversationDto

/** Unit tests for [resolveConversationTitle]; see its doc for why it's a plain testable function. */
class ConversationTitleTest {

  private val conversations = listOf(
    ConversationDto(threadId = 1L, title = "Alice", lastBody = "hi", timestamp = 100L, unread = 0),
    ConversationDto(threadId = 2L, title = "Team Signal", lastBody = "standup", timestamp = 200L, unread = 3)
  )

  @Test
  fun `returns the matching conversation's title`() {
    assertEquals("Alice", resolveConversationTitle(conversations, threadId = 1L, fallback = "Conversation"))
    assertEquals("Team Signal", resolveConversationTitle(conversations, threadId = 2L, fallback = "Conversation"))
  }

  @Test
  fun `falls back when the thread id isn't in the list`() {
    assertEquals("Conversation", resolveConversationTitle(conversations, threadId = 99L, fallback = "Conversation"))
  }

  @Test
  fun `falls back on an empty conversation list`() {
    assertEquals("Gesprek", resolveConversationTitle(emptyList(), threadId = 1L, fallback = "Gesprek"))
  }
}
