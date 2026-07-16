package org.thoughtcrime.securesms.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import org.thoughtcrime.securesms.wear.bridge.WearDataClient
import org.thoughtcrime.securesms.wear.data.WearConversationRepository
import org.thoughtcrime.securesms.wear.data.db.WearCacheDatabase
import org.thoughtcrime.securesms.wear.ui.ConversationListScreen
import org.thoughtcrime.securesms.wear.ui.ConversationScreen
import org.thoughtcrime.securesms.wear.ui.WearConversationViewModel

private const val ROUTE_CONVERSATIONS = "conversations"
private const val ROUTE_CONVERSATION = "conversation/{threadId}"

/**
 * Milestone 2 (WEAR-002) entry screen: a two-destination [SwipeDismissableNavHost] — the
 * conversation list ([ConversationListScreen]) and a single thread ([ConversationScreen]), reached
 * by tapping a row. Replaces the Milestone 1 (WEAR-001) ping button; [WearMessageListenerService]'s
 * pong handling ([org.thoughtcrime.securesms.wear.bridge.LastReply]) is untouched, it's just no
 * longer surfaced by this Activity.
 *
 * [viewModel] is held as a plain field rather than obtained through a [androidx.lifecycle.ViewModelProvider]:
 * this is a single, simple screen and there's no meaningful state to preserve across the
 * system-initiated recreation a [androidx.lifecycle.ViewModelProvider] would survive (the
 * conversation list re-syncs from Room instantly, and an in-flight thread re-requests its messages
 * on the next composition) — consistent with the process-wide-singleton style already used by
 * [org.thoughtcrime.securesms.wear.bridge.LastReply] and
 * [org.thoughtcrime.securesms.wear.data.WearMessagesSink] elsewhere in this module.
 */
class WearMainActivity : ComponentActivity() {

  private val viewModel: WearConversationViewModel by lazy {
    val repository = WearConversationRepository(
      dao = WearCacheDatabase.getInstance(applicationContext).wearConversationDao(),
      dataClient = WearDataClient(applicationContext)
    )
    WearConversationViewModel(repository)
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    setContent {
      MaterialTheme {
        val navController = rememberSwipeDismissableNavController()
        val conversations by viewModel.conversations.collectAsState()
        val messages by viewModel.messages.collectAsState()

        SwipeDismissableNavHost(
          navController = navController,
          startDestination = ROUTE_CONVERSATIONS,
          modifier = Modifier.fillMaxSize()
        ) {
          composable(ROUTE_CONVERSATIONS) {
            ConversationListScreen(
              conversations = conversations,
              onRefresh = viewModel::refresh,
              onOpen = { threadId -> navController.navigate("conversation/$threadId") },
              modifier = Modifier.fillMaxSize()
            )
          }

          composable(ROUTE_CONVERSATION) { backStackEntry ->
            val threadId = backStackEntry.arguments?.getString("threadId")?.toLongOrNull()
            if (threadId != null) {
              ConversationScreen(
                threadId = threadId,
                payload = messages,
                onOpen = viewModel::open,
                onReply = viewModel::reply,
                modifier = Modifier.fillMaxSize()
              )
            }
          }
        }
      }
    }
  }
}
