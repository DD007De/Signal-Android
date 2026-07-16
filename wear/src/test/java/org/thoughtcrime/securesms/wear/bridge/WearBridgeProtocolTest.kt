package org.thoughtcrime.securesms.wear.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WearBridgeProtocolTest {

  @Test
  fun paths_are_namespaced_and_distinct() {
    assertTrue(WearBridgeProtocol.PATH_PING.startsWith("/wear-bridge/"))
    assertTrue(WearBridgeProtocol.PATH_PONG.startsWith("/wear-bridge/"))
    assertNotEquals(WearBridgeProtocol.PATH_PING, WearBridgeProtocol.PATH_PONG)
  }

  @Test
  fun capability_is_stable() {
    assertEquals("signal_wear_bridge", WearBridgeProtocol.CAPABILITY)
  }
}
