package org.thoughtcrime.securesms.wear.notify

import org.junit.Assert.assertEquals
import org.junit.Test

/** Unit tests for [WearNotifier.notificationText]; see its doc for why it's a plain testable function. */
class WearNotifierTest {

  @Test
  fun `notificationText uses body when present, generic fallback when blank`() {
    assertEquals("Hoi", WearNotifier.notificationText("Hoi", "Nieuw bericht"))
    assertEquals("Nieuw bericht", WearNotifier.notificationText("", "Nieuw bericht"))
    assertEquals("Nieuw bericht", WearNotifier.notificationText("   ", "Nieuw bericht"))
  }
}
