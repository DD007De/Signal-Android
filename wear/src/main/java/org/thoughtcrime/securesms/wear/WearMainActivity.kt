package org.thoughtcrime.securesms.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.google.android.gms.wearable.Wearable
import org.signal.core.util.wear.WearBridgeProtocol
import org.thoughtcrime.securesms.wear.bridge.WearDataClient
import org.thoughtcrime.securesms.wear.data.WearConversationRepository
import org.thoughtcrime.securesms.wear.data.db.WearCacheDatabase
import org.thoughtcrime.securesms.wear.ui.ConversationListScreen
import org.thoughtcrime.securesms.wear.ui.ConversationScreen
import org.thoughtcrime.securesms.wear.ui.SignalWearTheme
import org.thoughtcrime.securesms.wear.ui.WearConversationViewModel
import org.thoughtcrime.securesms.wear.ui.resolveConversationTitle

private const val ROUTE_CONVERSATIONS = "conversations"
private const val ARG_THREAD_ID = "threadId"
private const val ROUTE_CONVERSATION = "conversation/{$ARG_THREAD_ID}"

/**
 * Milestone 2 (WEAR-002) entry screen: a two-destination [SwipeDismissableNavHost] — the
 * conversation list ([ConversationListScreen]) and a single thread ([ConversationScreen]), reached
 * by tapping a row. Replaces the Milestone 1 (WEAR-001) ping button; [WearMessageListenerService]'s
 * pong handling ([org.thoughtcrime.securesms.wear.bridge.LastReply]) is untouched, it's just no
 * longer surfaced by this Activity.
 *
 * [viewModel] is obtained through the standard [androidx.activity.viewModels] delegate (backed by a
 * [ViewModelProvider.Factory]) rather than held as a plain field: that ties it to this Activity's
 * [androidx.lifecycle.ViewModelStore], so `onCleared()` — and, with it, cancellation of
 * `viewModelScope` and the `SharingStarted.Eagerly` Room-flow collector in
 * [WearConversationViewModel.conversations] — actually runs when the Activity is finished, instead
 * of leaking a new collector on every recreation.
 */
class WearMainActivity : ComponentActivity() {

  private val viewModel: WearConversationViewModel by viewModels {
    object : ViewModelProvider.Factory {
      override fun <T : ViewModel> create(modelClass: Class<T>): T {
        val repository = WearConversationRepository(
          dao = WearCacheDatabase.getInstance(applicationContext).wearConversationDao(),
          dataClient = WearDataClient(applicationContext)
        )
        @Suppress("UNCHECKED_CAST")
        return WearConversationViewModel(repository) as T
      }
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    // Advertise the bridge capability at runtime so the phone can discover this watch (push / wipe /
    // avatars). The static android_wear_capabilities resource is not reliably picked up by GmsCore.
    Wearable.getCapabilityClient(applicationContext).addLocalCapability(WearBridgeProtocol.CAPABILITY)

    setContent {
      SignalWearTheme {
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

          composable(
            route = ROUTE_CONVERSATION,
            arguments = listOf(navArgument(ARG_THREAD_ID) { type = NavType.LongType })
          ) { backStackEntry ->
            val threadId = backStackEntry.arguments?.getLong(ARG_THREAD_ID)
            if (threadId != null) {
              val title = resolveConversationTitle(
                conversations = conversations,
                threadId = threadId,
                fallback = stringResource(R.string.wear_conversation_title_fallback)
              )
              ConversationScreen(
                threadId = threadId,
                title = title,
                payload = messages,
                onOpen = viewModel::open,
                onReply = viewModel::reply,
                onMarkRead = viewModel::markRead,
                onMute = viewModel::mute,
                onUnmute = viewModel::unmute,
                modifier = Modifier.fillMaxSize()
              )
            }
          }
        }
      }
    }
  }
}
