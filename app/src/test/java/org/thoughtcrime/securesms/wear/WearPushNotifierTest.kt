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
import org.signal.core.util.wear.ConversationsPayload
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

  // region helpers

  private fun context() = ApplicationProvider.getApplicationContext<Application>()

  private fun setPrivacy(preference: String) {
    every { recipients.signalStore.settings.messageNotificationsPrivacy } returns NotificationPrivacyPreference(preference)
  }

  // endregion
}
