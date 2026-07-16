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
import assertk.assertions.isTrue
import io.mockk.every
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.util.wear.ConversationsPayload
import org.signal.core.util.wear.MessagesPayload
import org.signal.core.util.wear.WearBridgeProtocol
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.preferences.widgets.NotificationPrivacyPreference
import org.thoughtcrime.securesms.testutil.RecipientTestRule
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Verifies [WearBridgeListenerService.handleMessage]'s Milestone 2 request/response paths without
 * a real GmsCore Wearable Data Layer: a fake [WearBridgeListenerService.WearResponder] captures
 * the outgoing (nodeId, path, bytes) so it can be decoded and asserted against data seeded
 * directly into the database (mirrors [WearBridgeRepositoryTest]'s setup). Also covers the M1
 * ping/pong smoke test, which is kept as-is.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class WearBridgeListenerServiceTest {

  @get:Rule
  val recipients = RecipientTestRule()

  @Test
  fun ping_stillSendsEmptyPongToSourceNode() {
    val (nodeId, path, bytes) = captureResponse { responder ->
      WearBridgeListenerService.handleMessage(
        context = context(),
        path = WearBridgeProtocol.PATH_PING,
        data = ByteArray(0),
        sourceNodeId = "watch-node",
        responder = responder
      )
    }

    assertThat(nodeId).isEqualTo("watch-node")
    assertThat(path).isEqualTo(WearBridgeProtocol.PATH_PONG)
    assertThat(bytes.toList()).isEmpty()
  }

  @Test
  fun requestConversations_sendsEncodedConversationsPayloadBackToSourceNode() {
    setPrivacy("all")

    val senderId = recipients.createRecipient("Alice Anderson")
    recipients.insertIncomingMessage(senderId)

    val (nodeId, path, bytes) = captureResponse { responder ->
      WearBridgeListenerService.handleMessage(
        context = context(),
        path = WearBridgeProtocol.PATH_REQUEST_CONVERSATIONS,
        data = ByteArray(0),
        sourceNodeId = "watch-node",
        responder = responder
      )
    }

    assertThat(nodeId).isEqualTo("watch-node")
    assertThat(path).isEqualTo(WearBridgeProtocol.PATH_CONVERSATIONS)

    val payload = WearBridgeProtocol.decode<ConversationsPayload>(bytes)
    assertThat(payload.conversations).hasSize(1)
    assertThat(payload.conversations[0].title).isEqualTo("Alice Anderson")
    assertThat(payload.conversations[0].lastBody).isEqualTo("hi")
  }

  @Test
  fun requestMessages_decodesThreadIdFromDataAndSendsEncodedMessagesPayload() {
    setPrivacy("all")

    val senderId = recipients.createRecipient("Bob Brown")
    recipients.insertIncomingMessage(senderId)
    val threadId = SignalDatabase.threads.getOrCreateThreadIdFor(senderId, false)

    val (nodeId, path, bytes) = captureResponse { responder ->
      WearBridgeListenerService.handleMessage(
        context = context(),
        path = WearBridgeProtocol.PATH_REQUEST_MESSAGES,
        data = threadId.toString().encodeToByteArray(),
        sourceNodeId = "watch-node",
        responder = responder
      )
    }

    assertThat(nodeId).isEqualTo("watch-node")
    assertThat(path).isEqualTo(WearBridgeProtocol.PATH_MESSAGES)

    val payload = WearBridgeProtocol.decode<MessagesPayload>(bytes)
    assertThat(payload.threadId).isEqualTo(threadId)
    assertThat(payload.messages).hasSize(1)
    assertThat(payload.messages[0].author).isEqualTo("Bob Brown")
    assertThat(payload.messages[0].body).isEqualTo("hi")
  }

  // region helpers

  private fun context() = ApplicationProvider.getApplicationContext<Application>()

  private fun setPrivacy(preference: String) {
    every { recipients.signalStore.settings.messageNotificationsPrivacy } returns NotificationPrivacyPreference(preference)
  }

  /**
   * Invokes [block] with a [WearBridgeListenerService.WearResponder] that captures the first
   * outgoing send and signals completion, then waits for it — [WearBridgeListenerService.handleMessage]
   * does its DB read and send off-thread via `SignalExecutors.BOUNDED` for the M2 paths.
   */
  private fun captureResponse(block: (WearBridgeListenerService.WearResponder) -> Unit): Triple<String, String, ByteArray> {
    val latch = CountDownLatch(1)
    val captured = AtomicReference<Triple<String, String, ByteArray>>()

    block(
      WearBridgeListenerService.WearResponder { nodeId, path, bytes ->
        captured.set(Triple(nodeId, path, bytes))
        latch.countDown()
      }
    )

    assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue()
    return captured.get()
  }

  // endregion
}
