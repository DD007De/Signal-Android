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
      WearBridgeProtocol.PATH_WIPE,
      WearBridgeProtocol.PATH_MARK_READ,
      WearBridgeProtocol.PATH_MUTE,
      WearBridgeProtocol.PATH_AVATAR
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
        ConversationDto(threadId = 7, title = "Alice", lastBody = "hi", timestamp = 123, unread = 2, avatarColor = 0xFFAABBCC.toInt(), initials = "AL"),
        ConversationDto(threadId = 8, title = "Bob", lastBody = "", timestamp = 456, unread = 0, avatarColor = 0, initials = "")
      )
    )
    val decoded: ConversationsPayload = WearBridgeProtocol.decode(WearBridgeProtocol.encode(payload))
    assertEquals(payload, decoded)
  }

  @Test
  fun conversation_dto_avatar_fields_default_for_backward_compatibility() {
    // Simulates decoding a payload from a sender that predates Milestone 4 Task A (no
    // avatarColor/initials keys in the JSON at all) -- ignoreUnknownKeys handles the forward
    // case (new sender, old receiver); this exercises the reverse, older-sender case, relying on
    // the field defaults declared on ConversationDto.
    val legacyJson = """{"version":1,"conversations":[{"threadId":7,"title":"Alice","lastBody":"hi","timestamp":123,"unread":2}]}"""
    val decoded: ConversationsPayload = WearBridgeProtocol.decode(legacyJson.encodeToByteArray())

    assertEquals(1, decoded.conversations.size)
    assertEquals(0, decoded.conversations[0].avatarColor)
    assertEquals("", decoded.conversations[0].initials)
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

  @Test
  fun mute_request_round_trips() {
    val request = MuteRequest(threadId = 11, muteUntil = 1_700_000_000_000L)
    val decoded: MuteRequest = WearBridgeProtocol.decode(WearBridgeProtocol.encode(request))
    assertEquals(request, decoded)
  }

  @Test
  fun `NotifyDto round-trips through encode-decode`() {
    val dto = NotifyDto(threadId = 42L, title = "Jan Willem", body = "Hoi", timestamp = 1_700_000_000_000L)
    val decoded = WearBridgeProtocol.decode<NotifyDto>(WearBridgeProtocol.encode(dto))
    assertEquals(dto, decoded)
  }

  @Test
  fun `NotifyDto with blank body (privacy-hidden) round-trips`() {
    val dto = NotifyDto(threadId = 7L, title = "Signal", body = "", timestamp = 1L)
    assertEquals(dto, WearBridgeProtocol.decode<NotifyDto>(WearBridgeProtocol.encode(dto)))
  }
}
