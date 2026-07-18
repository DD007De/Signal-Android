/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.wear

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import io.mockk.every
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.util.wear.ConversationDto
import org.signal.core.util.wear.ConversationsPayload
import org.signal.core.util.wear.NotifyDto
import org.signal.core.util.wear.WearBridgeProtocol
import org.thoughtcrime.securesms.preferences.widgets.NotificationPrivacyPreference
import org.thoughtcrime.securesms.testutil.RecipientTestRule

/**
 * Verifies [WearPushNotifier.pushToReachableNodes] — the testable core of the M2 push path that
 * [org.thoughtcrime.securesms.notifications.v2.DefaultMessageNotifier.updateNotification] triggers
 * on every notification refresh. As with [WearBridgeListenerServiceTest], a fake
 * [WearBridgeListenerService.WearResponder] captures outgoing (nodeId, path, bytes) sends without a
 * real GmsCore Wearable Data Layer, and data is seeded directly into the database (mirrors
 * [WearBridgeRepositoryTest]'s setup). Unlike [WearBridgeListenerService.handleMessage],
 * [WearPushNotifier.pushToReachableNodes] itself runs synchronously (the async/self-guarding
 * dispatch to `SignalExecutors.BOUNDED` lives one layer up, in [WearPushNotifier.onNotificationRefreshed],
 * which is not exercised here since it talks to the real Wearable Data Layer).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class WearPushNotifierTest {

  @get:Rule
  val recipients = RecipientTestRule()

  @Test
  fun pushesEncodedConversationsPayloadToEachReachableNode() {
    setPrivacy("all")

    val senderId = recipients.createRecipient("Alice Anderson")
    recipients.insertIncomingMessage(senderId)

    val captured = mutableListOf<Triple<String, String, ByteArray>>()
    val responder = WearBridgeListenerService.WearResponder { nodeId, path, bytes ->
      captured += Triple(nodeId, path, bytes)
    }

    WearPushNotifier.pushToReachableNodes(context(), listOf("watch-node-1", "watch-node-2"), responder)

    assertThat(captured).hasSize(2)

    val sentNodeIds = captured.map { it.first }
    assertThat(sentNodeIds).contains("watch-node-1")
    assertThat(sentNodeIds).contains("watch-node-2")

    captured.forEach { (_, path, bytes) ->
      assertThat(path).isEqualTo(WearBridgeProtocol.PATH_CONVERSATIONS)

      val payload = WearBridgeProtocol.decode<ConversationsPayload>(bytes)
      assertThat(payload.conversations).hasSize(1)
      assertThat(payload.conversations[0].title).isEqualTo("Alice Anderson")
      assertThat(payload.conversations[0].lastBody).isEqualTo("hi")
    }
  }

  @Test
  fun noReachableNodes_doesNotSendAnything() {
    setPrivacy("all")

    val senderId = recipients.createRecipient("Carol Carter")
    recipients.insertIncomingMessage(senderId)

    val captured = mutableListOf<Triple<String, String, ByteArray>>()
    val responder = WearBridgeListenerService.WearResponder { nodeId, path, bytes ->
      captured += Triple(nodeId, path, bytes)
    }

    WearPushNotifier.pushToReachableNodes(context(), emptyList(), responder)

    assertThat(captured).isEmpty()
  }

  @Test
  fun pushesNotifyForEachAlertedThreadWithPrivacyFilteredContent() {
    val captured = mutableListOf<Triple<String, String, ByteArray>>()
    val responder = WearBridgeListenerService.WearResponder { nodeId, path, bytes ->
      captured += Triple(nodeId, path, bytes)
    }

    WearPushNotifier.pushNotificationsToNodes(
      conversations = listOf(
        ConversationDto(threadId = 42L, title = "Jan Willem", lastBody = "Hoi", timestamp = 5L, unread = 1),
        ConversationDto(threadId = 99L, title = "Other", lastBody = "x", timestamp = 4L, unread = 0)
      ),
      nodeIds = listOf("nodeA"),
      threadIds = listOf(42L),
      responder = responder
    )

    assertThat(captured).hasSize(1)
    val (nodeId, path, bytes) = captured.single()
    assertThat(nodeId).isEqualTo("nodeA")
    assertThat(path).isEqualTo(WearBridgeProtocol.PATH_NOTIFY)

    val dto = WearBridgeProtocol.decode<NotifyDto>(bytes)
    assertThat(dto.threadId).isEqualTo(42L)
    assertThat(dto.title).isEqualTo("Jan Willem")
    assertThat(dto.body).isEqualTo("Hoi")
    assertThat(dto.timestamp).isEqualTo(5L)
  }

  @Test
  fun pushNotificationsToNodes_skipsThreadIdsAbsentFromConversations() {
    val captured = mutableListOf<Triple<String, String, ByteArray>>()
    val responder = WearBridgeListenerService.WearResponder { nodeId, path, bytes ->
      captured += Triple(nodeId, path, bytes)
    }

    WearPushNotifier.pushNotificationsToNodes(
      conversations = listOf(
        ConversationDto(threadId = 42L, title = "Jan Willem", lastBody = "Hoi", timestamp = 5L, unread = 1)
      ),
      nodeIds = listOf("nodeA"),
      threadIds = listOf(42L, 999L),
      responder = responder
    )

    assertThat(captured).hasSize(1)
    val (nodeId, path, bytes) = captured.single()
    assertThat(nodeId).isEqualTo("nodeA")
    assertThat(path).isEqualTo(WearBridgeProtocol.PATH_NOTIFY)

    val dto = WearBridgeProtocol.decode<NotifyDto>(bytes)
    assertThat(dto.threadId).isEqualTo(42L)
  }

  @Test
  fun pushNotificationsToNodes_skipsExcludedWatchOpenThread() {
    val captured = mutableListOf<Triple<String, String, ByteArray>>()
    val responder = WearBridgeListenerService.WearResponder { nodeId, path, bytes ->
      captured += Triple(nodeId, path, bytes)
    }

    WearPushNotifier.pushNotificationsToNodes(
      conversations = listOf(
        ConversationDto(threadId = 42L, title = "Jan", lastBody = "Hoi", timestamp = 5L, unread = 1),
        ConversationDto(threadId = 43L, title = "Kira", lastBody = "Hi", timestamp = 4L, unread = 1)
      ),
      nodeIds = listOf("n"),
      threadIds = listOf(42L, 43L),
      responder = responder,
      excludeThreadId = 42L
    )

    assertThat(captured).hasSize(1)
    val dto = WearBridgeProtocol.decode<NotifyDto>(captured.single().third)
    assertThat(dto.threadId).isEqualTo(43L)
  }

  @Test
  fun pushNotificationsToNodes_isNoOpWithNoNodesOrNoThreads() {
    val responder = WearBridgeListenerService.WearResponder { _, _, _ -> throw AssertionError("should not send") }

    WearPushNotifier.pushNotificationsToNodes(emptyList(), emptyList(), listOf(1L), responder)
    WearPushNotifier.pushNotificationsToNodes(listOf(ConversationDto(1L, "t", "b", 1L, 0)), listOf("n"), emptyList(), responder)
  }

  // region helpers

  private fun context() = ApplicationProvider.getApplicationContext<Application>()

  private fun setPrivacy(preference: String) {
    every { recipients.signalStore.settings.messageNotificationsPrivacy } returns NotificationPrivacyPreference(preference)
  }

  // endregion
}
