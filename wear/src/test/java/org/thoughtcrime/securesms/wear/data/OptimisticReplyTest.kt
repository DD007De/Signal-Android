package org.thoughtcrime.securesms.wear.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.signal.core.util.wear.MessageDto
import org.signal.core.util.wear.MessagesPayload

/** Unit tests for [appendOptimisticReply]; see its doc for why it's a plain testable function. */
class OptimisticReplyTest {

  @Test
  fun `appendOptimisticReply adds an outgoing message to the open thread`() {
    val current = MessagesPayload(threadId = 42L, messages = listOf(MessageDto("Jan", "Hoi", 1L, outgoing = false)))
    val next = appendOptimisticReply(current, threadId = 42L, body = "Terug", timestamp = 2L)!!
    assertEquals(2, next.messages.size)
    val appended = next.messages.last()
    assertTrue(appended.outgoing)
    assertEquals("Terug", appended.body)
    assertEquals(2L, appended.timestamp)
  }

  @Test
  fun `appendOptimisticReply is a no-op for a different or absent thread`() {
    assertEquals(null, appendOptimisticReply(null, 42L, "x", 1L))
    val other = MessagesPayload(threadId = 7L, messages = emptyList())
    assertEquals(other, appendOptimisticReply(other, 42L, "x", 1L))
  }
}
