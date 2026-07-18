package org.signal.core.util.wear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WearBridgeProtocolTest {

  @Test
  fun paths_are_namespaced_and_distinct() {
    val paths = listOf(
      WearBridgeProtocol.PATH_PING,
      WearBridgeProtocol.PATH_PONG,
      WearBridgeProtocol.PATH_REQUEST_CONVERSATIONS,
      WearBridgeProtocol.PATH_CONVERSATIONS,
      WearBridgeProtocol.PATH_REQUEST_MESSAGES,
      WearBridgeProtocol.PATH_MESSAGES,
      WearBridgeProtocol.PATH_SEND_REPLY,
      WearBridgeProtocol.PATH_WIPE
    )
    paths.forEach { assertTrue("$it should be namespaced", it.startsWith("/wear-bridge/")) }
    assertEquals("all paths must be distinct", paths.size, paths.toSet().size)
  }

  @Test
  fun capability_and_version_are_stable() {
    assertEquals("signal_wear_bridge", WearBridgeProtocol.CAPABILITY)
    assertEquals(1, WearBridgeProtocol.PROTOCOL_VERSION)
  }

  @Test
  fun conversations_payload_round_trips() {
    val payload = ConversationsPayload(
      conversations = listOf(
        ConversationDto(threadId = 7, title = "Alice", lastBody = "hi", timestamp = 123, unread = 2),
        ConversationDto(threadId = 8, title = "Bob", lastBody = "", timestamp = 456, unread = 0)
      )
    )
    val decoded: ConversationsPayload = WearBridgeProtocol.decode(WearBridgeProtocol.encode(payload))
    assertEquals(payload, decoded)
  }

  @Test
  fun messages_payload_round_trips() {
    val payload = MessagesPayload(
      threadId = 7,
      messages = listOf(
        MessageDto(author = "Alice", body = "hi", timestamp = 123, outgoing = false),
        MessageDto(author = "You", body = "hey", timestamp = 124, outgoing = true)
      )
    )
    val decoded: MessagesPayload = WearBridgeProtocol.decode(WearBridgeProtocol.encode(payload))
    assertEquals(payload, decoded)
  }

  @Test
  fun reply_request_round_trips() {
    val request = ReplyRequest(threadId = 9, body = "hello from watch")
    val decoded: ReplyRequest = WearBridgeProtocol.decode(WearBridgeProtocol.encode(request))
    assertEquals(request, decoded)
  }
}
