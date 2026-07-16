package org.thoughtcrime.securesms.wear.bridge

import androidx.compose.runtime.mutableStateOf
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import org.signal.core.util.wear.WearBridgeProtocol

/**
 * Watch-side receiver. Milestone 1 only handles the pong reply from the phone and surfaces it
 * through [LastReply] so the entry Activity can render it.
 */
class WearMessageListenerService : WearableListenerService() {
  override fun onMessageReceived(event: MessageEvent) {
    if (event.path == WearBridgeProtocol.PATH_PONG) {
      LastReply.state.value = "pong @ ${event.sourceNodeId.take(4)}"
    }
  }
}

/**
 * Trivial process-wide sink so the Milestone 1 Activity can observe the reply without a real
 * repository/state layer. Replaced by proper state in Milestone 2.
 */
object LastReply {
  val state = mutableStateOf("idle")
}
