/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.wear

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import org.junit.Test
import org.signal.core.util.wear.WearBridgeProtocol

/**
 * Verifies [WearWipeNotifier.wipeReachableNodes] — the testable core of the WEAR-002 Task 9
 * logout-wipe path. As with [WearPushNotifierTest], a fake [WearBridgeListenerService.WearResponder]
 * captures outgoing (nodeId, path, bytes) sends without a real GmsCore Wearable Data Layer.
 * [WearWipeNotifier.onLogout] itself (the async/self-guarding dispatch to `SignalExecutors.BOUNDED`
 * plus the real `CapabilityClient` lookup) is not exercised here, for the same reason
 * [WearPushNotifierTest] doesn't exercise `WearPushNotifier.onNotificationRefreshed`: it talks to
 * the real Wearable Data Layer and is left to on-device verification.
 */
class WearWipeNotifierTest {

  @Test
  fun sendsEmptyBodyPathWipeToEachReachableNode() {
    val captured = mutableListOf<Triple<String, String, ByteArray>>()
    val responder = WearBridgeListenerService.WearResponder { nodeId, path, bytes ->
      captured += Triple(nodeId, path, bytes)
    }

    WearWipeNotifier.wipeReachableNodes(listOf("watch-node-1", "watch-node-2"), responder)

    assertThat(captured).hasSize(2)

    val sentNodeIds = captured.map { it.first }
    assertThat(sentNodeIds).contains("watch-node-1")
    assertThat(sentNodeIds).contains("watch-node-2")

    captured.forEach { (_, path, bytes) ->
      assertThat(path).isEqualTo(WearBridgeProtocol.PATH_WIPE)
      assertThat(bytes.toList()).isEmpty()
    }
  }

  @Test
  fun noReachableNodes_doesNotSendAnything() {
    val captured = mutableListOf<Triple<String, String, ByteArray>>()
    val responder = WearBridgeListenerService.WearResponder { nodeId, path, bytes ->
      captured += Triple(nodeId, path, bytes)
    }

    WearWipeNotifier.wipeReachableNodes(emptyList(), responder)

    assertThat(captured).isEmpty()
  }
}
