package org.signal.core.util.wear

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The wire contract for the Wear OS companion bridge, shared by the phone (`:app`) and the watch
 * (`:wear`) over the Google Wearable Data Layer. Unified here in Milestone 2 (it was duplicated on
 * both sides during the Milestone 1 spike).
 *
 * Payloads are serialized to compact JSON bytes via [encode]/[decode].
 */
object WearBridgeProtocol {
  const val CAPABILITY = "signal_wear_bridge"
  const val PROTOCOL_VERSION = 1

  // --- Milestone 1 transport smoke-test paths (deliberately retained in M2 as a transport smoke test). ---
  const val PATH_PING = "/wear-bridge/ping"
  const val PATH_PONG = "/wear-bridge/pong"

  // --- Milestone 2 request/response + push paths. ---
  const val PATH_REQUEST_CONVERSATIONS = "/wear-bridge/conversations/request" // watch -> phone
  const val PATH_CONVERSATIONS = "/wear-bridge/conversations" // phone -> watch (response + push)
  const val PATH_REQUEST_MESSAGES = "/wear-bridge/messages/request" // watch -> phone (per thread)
  const val PATH_MESSAGES = "/wear-bridge/messages" // phone -> watch
  const val PATH_SEND_REPLY = "/wear-bridge/reply/send" // watch -> phone

  @PublishedApi
  internal val json = Json { ignoreUnknownKeys = true }

  inline fun <reified T> encode(value: T): ByteArray = json.encodeToString(value).encodeToByteArray()

  inline fun <reified T> decode(bytes: ByteArray): T = json.decodeFromString(bytes.decodeToString())
}

@Serializable
data class ConversationDto(
  val threadId: Long,
  val title: String,
  val lastBody: String,
  val timestamp: Long,
  val unread: Int
)

@Serializable
data class ConversationsPayload(
  val version: Int = WearBridgeProtocol.PROTOCOL_VERSION,
  val conversations: List<ConversationDto>
)

@Serializable
data class MessageDto(
  val author: String,
  val body: String,
  val timestamp: Long,
  val outgoing: Boolean
)

@Serializable
data class MessagesPayload(
  val version: Int = WearBridgeProtocol.PROTOCOL_VERSION,
  val threadId: Long,
  val messages: List<MessageDto>
)

@Serializable
data class ReplyRequest(
  val version: Int = WearBridgeProtocol.PROTOCOL_VERSION,
  val threadId: Long,
  val body: String
)
