package org.thoughtcrime.securesms.wear.bridge

import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.mutableStateOf
import com.google.android.gms.wearable.CapabilityInfo
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.signal.core.util.logging.Log
import org.signal.core.util.wear.ConversationDto
import org.signal.core.util.wear.ConversationsPayload
import org.signal.core.util.wear.MessagesPayload
import org.signal.core.util.wear.WearBridgeProtocol
import org.thoughtcrime.securesms.wear.data.WearMessagesSink
import org.thoughtcrime.securesms.wear.data.db.WearCacheDatabase
import org.thoughtcrime.securesms.wear.data.db.WearConversationDao
import org.thoughtcrime.securesms.wear.data.db.WearConversationEntity

/**
 * Watch-side receiver. Milestone 1 (WEAR-001) only handled the pong reply from the phone and
 * surfaced it through [LastReply]; that handler is kept below as a transport smoke test.
 * Milestone 2 (WEAR-002) adds the real push paths: a fresh conversation list is synced into the
 * local Room cache, and a thread's messages are forwarded to [WearMessagesSink] for the UI to
 * observe (messages are not persisted, only conversations are).
 *
 * Privacy hardening (WEAR-002 Task 9) adds two ways the local cache gets wiped, on top of the
 * ordinary per-push [WearConversationDao.replaceAll] full-replace sync:
 * - [WearBridgeProtocol.PATH_WIPE]: an explicit phone -> watch signal (sent from
 *   [org.thoughtcrime.securesms.wear.WearWipeNotifier] on the phone side) handled in
 *   [handleIncoming] below, for when the phone-side account is logged out / deleted.
 * - [onCapabilityChanged]: a local, phone-independent signal for the "watch got unpaired" case,
 *   where the phone may never get a chance to send [WearBridgeProtocol.PATH_WIPE] at all.
 *
 * Lifecycle caveat (accepted for M2): [WearableListenerService] only guarantees the hosting
 * process stays alive for the synchronous duration of [onMessageReceived] itself; the actual work
 * here happens in a detached coroutine launched on [scope], which the system is free to cancel
 * (via [onDestroy]) once it decides the process is no longer needed. That can transiently drop an
 * in-flight push. Fully closing that gap (e.g. `goAsync()`/WorkManager-backed processing) is out
 * of scope for this milestone and needs on-device verification of the resulting lifecycle
 * behavior. It's an acceptable gap because the sync is self-healing: the next `refresh()` /
 * [WearBridgeProtocol.PATH_REQUEST_CONVERSATIONS] round-trip re-syncs the full, current state
 * regardless of what was missed.
 */
class WearMessageListenerService : WearableListenerService() {

  /**
   * Single-threaded on purpose: [WearBridgeProtocol.PATH_CONVERSATIONS] pushes are ordered,
   * full-replace syncs ([WearConversationDao.replaceAll]), so two pushes handled concurrently on a
   * multi-threaded dispatcher could race and let an older payload's `replaceAll` win, leaving the
   * cache stuck on stale data. Confining the scope to one thread (while still using the IO pool's
   * threads for the underlying execution) processes incoming messages strictly in arrival order.
   */
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

  override fun onMessageReceived(event: MessageEvent) {
    if (event.path == WearBridgeProtocol.PATH_PONG) {
      LastReply.state.value = "pong @ ${event.sourceNodeId.take(4)}"
      return
    }

    val dao = WearCacheDatabase.getInstance(applicationContext).wearConversationDao()
    scope.launch {
      try {
        handleIncoming(
          path = event.path,
          data = event.data,
          dao = dao,
          onMessages = { WearMessagesSink.state.value = it }
        )
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.w(TAG, "Failed to handle ${event.path} from ${event.sourceNodeId}", e)
      }
    }
  }

  override fun onDestroy() {
    super.onDestroy()
    scope.cancel()
  }

  /**
   * Fired by the Wearable Data Layer (registered via the `CAPABILITY_CHANGED` manifest
   * intent-filter, mirroring how `MESSAGE_RECEIVED` is registered above) whenever the set of
   * reachable nodes advertising [WearBridgeProtocol.CAPABILITY] changes. When that set goes empty
   * — no phone advertising the bridge capability is reachable any more, i.e. the watch was unpaired
   * from its phone, or the Signal app was uninstalled from it — the local cache is wiped, the same
   * as an explicit [WearBridgeProtocol.PATH_WIPE] push would do.
   *
   * The actual decision of whether to wipe is delegated to [shouldWipeForCapabilityChange], a pure
   * function unit-tested directly; this override itself talks to real GmsCore
   * ([CapabilityInfo]) and a real Room instance, so it is left to on-device verification (per the
   * class-level lifecycle caveat, the same caveat that applies to [onMessageReceived]).
   */
  override fun onCapabilityChanged(capabilityInfo: CapabilityInfo) {
    if (!shouldWipeForCapabilityChange(capabilityInfo.name, capabilityInfo.nodes.size)) {
      return
    }

    val dao = WearCacheDatabase.getInstance(applicationContext).wearConversationDao()
    scope.launch {
      try {
        dao.clear()
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.w(TAG, "Failed to clear cache after losing capability ${capabilityInfo.name}", e)
      }
    }
  }

  companion object {
    private val TAG = Log.tag(WearMessageListenerService::class.java)

    /**
     * Handles an incoming Milestone 2 push from the phone. Extracted from [onMessageReceived] so it
     * can be unit tested against an in-memory Room [WearConversationDao] without a real
     * [WearableListenerService] or GmsCore.
     *
     * - [WearBridgeProtocol.PATH_CONVERSATIONS]: decodes a [ConversationsPayload] and replaces the
     *   cache wholesale via [WearConversationDao.replaceAll], so a thread dropped from the phone's
     *   payload disappears from the watch too.
     * - [WearBridgeProtocol.PATH_MESSAGES]: decodes a [MessagesPayload] and forwards it to
     *   [onMessages]; messages are never written to [dao].
     * - [WearBridgeProtocol.PATH_WIPE]: privacy hardening (WEAR-002 Task 9) — the phone lost its
     *   account (logout / delete-all-data), so the body is ignored (it's empty) and [dao] is
     *   cleared wholesale via [WearConversationDao.clear], same as [onCapabilityChanged] does for
     *   the unpair case.
     *
     * Any other path is ignored (the M1 pong is handled separately in [onMessageReceived], before
     * this is called).
     */
    suspend fun handleIncoming(
      path: String,
      data: ByteArray,
      dao: WearConversationDao,
      onMessages: (MessagesPayload) -> Unit
    ) {
      when (path) {
        WearBridgeProtocol.PATH_CONVERSATIONS -> {
          val payload = WearBridgeProtocol.decode<ConversationsPayload>(data)
          dao.replaceAll(payload.conversations.map { it.toEntity() })
        }

        WearBridgeProtocol.PATH_MESSAGES -> {
          onMessages(WearBridgeProtocol.decode<MessagesPayload>(data))
        }

        WearBridgeProtocol.PATH_WIPE -> {
          dao.clear()
        }
      }
    }

    /**
     * Pure decision core of [onCapabilityChanged]: wipe only when the capability that changed is
     * the Wear bridge's own ([WearBridgeProtocol.CAPABILITY]) and no node advertising it is
     * reachable any more. Extracted so it can be unit tested without constructing a real
     * [CapabilityInfo], which requires GmsCore.
     */
    @VisibleForTesting
    internal fun shouldWipeForCapabilityChange(capabilityName: String, reachableNodeCount: Int): Boolean {
      return capabilityName == WearBridgeProtocol.CAPABILITY && reachableNodeCount == 0
    }

    private fun ConversationDto.toEntity(): WearConversationEntity = WearConversationEntity(
      threadId = threadId,
      title = title,
      lastBody = lastBody,
      timestamp = timestamp,
      unread = unread
    )
  }
}

/**
 * Trivial process-wide sink so the Milestone 1 Activity can observe the reply without a real
 * repository/state layer. Replaced by proper state in Milestone 2.
 */
object LastReply {
  val state = mutableStateOf("idle")
}
