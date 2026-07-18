package org.thoughtcrime.securesms.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import kotlinx.coroutines.launch
import org.thoughtcrime.securesms.wear.bridge.LastReply
import org.thoughtcrime.securesms.wear.bridge.WearDataClient

/**
 * Milestone 1 (WEAR-001) entry screen: a single button that pings the paired phone over the Data
 * Layer and shows the pong reply reported by [LastReply].
 */
class WearMainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    val dataClient = WearDataClient(applicationContext)

    setContent {
      MaterialTheme {
        val scope = rememberCoroutineScope()
        val reply by LastReply.state

        Button(
          onClick = {
            scope.launch {
              LastReply.state.value = "sending…"
              // On success, leave the displayed state to WearMessageListenerService's pong so a fast
              // reply is not clobbered; only report the no-node/failure case here.
              if (!dataClient.ping()) {
                LastReply.state.value = "no phone"
              }
            }
          },
          modifier = Modifier.fillMaxSize()
        ) {
          Text(text = reply)
        }
      }
    }
  }
}
