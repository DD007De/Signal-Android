package org.thoughtcrime.securesms.wear

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import org.thoughtcrime.securesms.wear.notify.WearNotifier
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

  /**
   * WEAR-005: the thread id (if any) a tapped [WearNotifier] notification deep-linked in with, read
   * from [WearNotifier.EXTRA_THREAD_ID]. Set from [onCreate]'s initial intent and re-set from
   * [onNewIntent] (the `FLAG_ACTIVITY_CLEAR_TOP` re-delivery path when the Activity is already
   * running); the composable's `LaunchedEffect` below consumes it by navigating and clearing it back
   * to null, so a later recomposition (e.g. rotating a bezel) doesn't re-navigate.
   */
  private val pendingDeepLinkThreadId = mutableStateOf<Long?>(null)

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    if (Build.VERSION.SDK_INT >= 33 &&
      checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) {
      requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
    }

    // Advertise the bridge capability at runtime so the phone can discover this watch (push / wipe /
    // avatars). The static android_wear_capabilities resource is not reliably picked up by GmsCore.
    Wearable.getCapabilityClient(applicationContext).addLocalCapability(WearBridgeProtocol.CAPABILITY)

    pendingDeepLinkThreadId.value = threadIdFromIntent(intent)

    setContent {
      SignalWearTheme {
        val navController = rememberSwipeDismissableNavController()
        val conversations by viewModel.conversations.collectAsState()
        val messages by viewModel.messages.collectAsState()
        val deepLinkThreadId by pendingDeepLinkThreadId

        LaunchedEffect(deepLinkThreadId) {
          val threadId = deepLinkThreadId
          if (threadId != null) {
            navController.navigate("conversation/$threadId") {
              launchSingleTop = true
              popUpTo(ROUTE_CONVERSATIONS) { inclusive = false }
            }
            pendingDeepLinkThreadId.value = null
          }
        }

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

  /**
   * Re-delivery path for a tapped [WearNotifier] notification while the Activity is already running
   * ([Intent.FLAG_ACTIVITY_CLEAR_TOP]) — [onCreate] only sees the launching intent, so this is what
   * lets a second (or third) tapped notification navigate again without recreating the Activity.
   */
  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    pendingDeepLinkThreadId.value = threadIdFromIntent(intent)
  }

  /** [WearNotifier.EXTRA_THREAD_ID] from [intent], or null when absent (a plain launcher tap). */
  private fun threadIdFromIntent(intent: Intent): Long? {
    val threadId = intent.getLongExtra(WearNotifier.EXTRA_THREAD_ID, -1L)
    return threadId.takeIf { it >= 0L }
  }
}
