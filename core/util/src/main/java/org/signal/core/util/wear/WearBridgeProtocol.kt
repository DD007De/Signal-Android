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

  // --- Privacy hardening (cache wipe on logout / unpair). ---
  const val PATH_WIPE = "/wear-bridge/wipe" // phone -> watch; empty body, wipes the local cache

  // --- Milestone 3 conversation action paths (watch -> phone). ---
  const val PATH_MARK_READ = "/wear-bridge/action/mark-read" // watch -> phone; body is the thread ID as UTF-8 text
  const val PATH_MUTE = "/wear-bridge/action/mute" // watch -> phone; body is an encoded MuteRequest

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
  val unread: Int,
  // Milestone 4 Task A: enough to draw Signal's colored-circle-with-initials fallback avatar on the
  // watch without sending photo bytes (MessageClient has a ~100KB message limit). Defaulted so
  // older senders/receivers that don't know about these fields still round-trip cleanly.
  val avatarColor: Int = 0,
  val initials: String = ""
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

@Serializable
data class MuteRequest(
  val version: Int = WearBridgeProtocol.PROTOCOL_VERSION,
  val threadId: Long,
  val muteUntil: Long
)
