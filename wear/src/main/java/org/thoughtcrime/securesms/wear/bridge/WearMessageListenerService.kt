package org.thoughtcrime.securesms.wear.bridge

import android.content.Context
import android.graphics.BitmapFactory
import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.CapabilityInfo
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.signal.core.util.logging.Log
import org.signal.core.util.wear.ConversationDto
import org.signal.core.util.wear.ConversationsPayload
import org.signal.core.util.wear.MessagesPayload
import org.signal.core.util.wear.WearBridgeProtocol
import org.thoughtcrime.securesms.wear.data.WearAvatarCache
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
 * ordinary per-push [WearConversationDao.replaceAll] full-replace sync. Both paths clear
 * [WearConversationDao] and [WearAvatarCache] together (a Milestone 4 Task D (WEAR-004) fix: a
 * cached contact face photo previously kept rendering after either wipe path fired, until the
 * process restarted):
 * - [WearBridgeProtocol.PATH_WIPE]: an explicit phone -> watch signal (sent from
 *   [org.thoughtcrime.securesms.wear.WearWipeNotifier] on the phone side) handled in
 *   [handleIncoming] below, for when the phone-side account is logged out / deleted.
 * - [onCapabilityChanged]: a local, phone-independent signal for the "watch got unpaired" case,
 *   where the phone may never get a chance to send [WearBridgeProtocol.PATH_WIPE] at all. This one
 *   is debounced (see [onCapabilityChanged]'s KDoc) — a review finding pointed out that reachability
 *   flips on routine transient events too (Bluetooth out of range, phone Doze/airplane
 *   mode/battery-dead), not just a genuine unpair, and wiping immediately on those would blank the
 *   watch UI for no reason.
 *
 * Lifecycle caveat (accepted for M2): [WearableListenerService] only guarantees the hosting
 * process stays alive for the synchronous duration of [onMessageReceived] itself; the actual work
 * here happens in a detached coroutine launched on [scope], which the system is free to cancel
 * (via [onDestroy]) once it decides the process is no longer needed. That can transiently drop an
 * in-flight push. Fully closing that gap (e.g. `goAsync()`/WorkManager-backed processing) is out
 * of scope for this milestone and needs on-device verification of the resulting lifecycle
 * behavior. It's an acceptable gap because the sync is self-healing: the next `refresh()` /
 * [WearBridgeProtocol.PATH_REQUEST_CONVERSATIONS] round-trip re-syncs the full, current state
 * regardless of what was missed. [onDataChanged] (Milestone 4 Task D) has the same accepted gap,
 * for the same reason: a photo dropped mid-flight is re-published the next time the phone re-syncs
 * that thread's avatar.
 *
 * Milestone 4 Task D (WEAR-004) adds [onDataChanged]: real contact photos, sent by the phone as
 * Data Layer Assets rather than [MessageEvent]s (which are capped at ~100KB), are decoded and
 * cached in [WearAvatarCache] for [org.thoughtcrime.securesms.wear.ui.ConversationAvatar] to render,
 * falling back to the existing colored-initials avatar when nothing is cached for a thread.
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
   * reachable nodes advertising [WearBridgeProtocol.CAPABILITY] changes.
   *
   * A review finding on the first cut of this (WEAR-002 Task 9) pointed out that "zero reachable
   * nodes" is not the same thing as "unpaired": reachability also flips on routine, transient
   * events — Bluetooth briefly out of range, the phone in Doze / airplane mode / with a dead
   * battery — none of which mean the account was actually unlinked. Wiping immediately on the
   * zero-node callback nuked the cache and blanked the watch UI on every one of those. So this is
   * now debounced: when [shouldWipeForCapabilityChange] says the bridge capability just dropped to
   * zero reachable nodes, a coroutine is launched on [scope] that [delay]s [UNPAIR_CONFIRM_MS] and
   * then re-queries [CapabilityClient] for the *current* reachable-node count; [dao] and
   * [WearAvatarCache] are only cleared if it is *still* zero after the wait, i.e. no node
   * reappeared during the window — both are cleared together (Milestone 4 Task D (WEAR-004) fix)
   * so a cached contact photo doesn't outlive the rest of the wipe. This mirrors an explicit
   * [WearBridgeProtocol.PATH_WIPE] push in effect, but only once the "unpaired" read has been
   * confirmed rather than acted on from a single, possibly-transient callback.
   *
   * The initial decision (whether to even start the confirm timer) and the re-check after the delay
   * both go through [shouldWipeForCapabilityChange], a pure function unit-tested directly. What
   * isn't unit-testable is the coroutine wrapper itself: it talks to real GmsCore ([CapabilityClient],
   * [CapabilityInfo]) and a real Room instance, so — like [onMessageReceived]'s equivalent gap — it
   * is left to on-device verification (per the class-level lifecycle caveat).
   */
  override fun onCapabilityChanged(capabilityInfo: CapabilityInfo) {
    if (!shouldWipeForCapabilityChange(capabilityInfo.name, capabilityInfo.nodes.size)) {
      return
    }

    val dao = WearCacheDatabase.getInstance(applicationContext).wearConversationDao()
    val context = applicationContext
    scope.launch {
      try {
        delay(UNPAIR_CONFIRM_MS)

        val reachableNodeCount = Wearable.getCapabilityClient(context)
          .getCapability(WearBridgeProtocol.CAPABILITY, CapabilityClient.FILTER_REACHABLE)
          .await()
          .nodes
          .size

        if (shouldWipeForCapabilityChange(WearBridgeProtocol.CAPABILITY, reachableNodeCount)) {
          dao.clear()
          WearAvatarCache.clear()
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.w(TAG, "Failed to clear cache after losing capability ${capabilityInfo.name}", e)
      }
    }
  }

  /**
   * Milestone 4 Task D (WEAR-004): handles [WearBridgeProtocol.PATH_AVATAR] DataItem puts/deletes
   * for real contact photos, registered via the `DATA_CHANGED` intent-filter (pathPrefix
   * `/wear-bridge/avatar`) alongside `MESSAGE_RECEIVED`/`CAPABILITY_CHANGED` above.
   *
   * [DataEventBuffer] is a GmsCore-owned view over native memory that's only valid for the
   * synchronous duration of this call — it's recycled the moment [onDataChanged] returns, the same
   * lifecycle caveat the class KDoc already calls out for [onMessageReceived]/[MessageEvent]. The
   * actual work here (fetching the asset's bytes via [com.google.android.gms.wearable.DataClient.getFdForAsset]
   * and decoding a bitmap) is I/O that belongs on [scope], so nothing from [dataEvents] is touched
   * from inside the launched coroutine: [dataEvents] is walked synchronously, right here, into
   * [pending] — a plain list of [PendingAvatarEvent] holding just a thread id and (for a changed
   * item) the [Asset] reference itself. An [Asset] is a small Parcelable handle, not a view over the
   * buffer's backing memory, so retaining it past this method returning is safe; the [DataEvent]/
   * [com.google.android.gms.wearable.DataItem] it came from is not retained.
   *
   * - [DataEvent.TYPE_CHANGED]: decodes the `"avatar"` [Asset] and caches the resulting
   *   [ImageBitmap] in [WearAvatarCache] under the thread id parsed from the DataItem's path (see
   *   [threadIdFromAvatarPath]). A decode failure (asset fetch error, corrupt bytes) removes any
   *   stale cache entry instead of leaving a possibly-wrong photo cached.
   * - [DataEvent.TYPE_DELETED]: removes the thread id from [WearAvatarCache] — the phone deletes
   *   the DataItem when a contact has no real photo, or notification-content privacy hides it.
   *
   * Not unit tested: needs a real [DataEventBuffer], [DataMapItem], and
   * [com.google.android.gms.wearable.DataClient.getFdForAsset], all of which require GmsCore.
   * Device-verified instead, consistent with this class's other GmsCore-touching gaps
   * ([onCapabilityChanged]'s coroutine body, [WearDataClient]'s send methods). [threadIdFromAvatarPath]
   * itself, the one pure piece, is unit tested directly.
   */
  override fun onDataChanged(dataEvents: DataEventBuffer) {
    val pending = dataEvents.mapNotNull { toPendingAvatarEvent(it) }
    if (pending.isEmpty()) {
      return
    }

    val context = applicationContext
    scope.launch {
      try {
        pending.forEach { applyAvatarEvent(context, it) }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.w(TAG, "Failed to process avatar data event(s)", e)
      }
    }
  }

  /**
   * Synchronous extraction step for [onDataChanged]: pulls only the buffer-independent bits an
   * incoming [DataEvent] needs (thread id, and for a changed item, the [Asset] reference) out of it
   * before the buffer is recycled. Returns null for events outside [WearBridgeProtocol.PATH_AVATAR],
   * a malformed path (see [threadIdFromAvatarPath]), an event type other than
   * [DataEvent.TYPE_CHANGED]/[DataEvent.TYPE_DELETED], or a changed item with no `"avatar"` asset.
   */
  private fun toPendingAvatarEvent(event: DataEvent): PendingAvatarEvent? {
    val path = event.dataItem.uri.path ?: return null
    val threadId = threadIdFromAvatarPath(path) ?: return null

    return when (event.type) {
      DataEvent.TYPE_DELETED -> PendingAvatarEvent(threadId, asset = null)
      DataEvent.TYPE_CHANGED -> {
        val asset = DataMapItem.fromDataItem(event.dataItem).dataMap.getAsset(ASSET_KEY_AVATAR) ?: return null
        PendingAvatarEvent(threadId, asset = asset)
      }
      else -> null
    }
  }

  /**
   * The async half of [onDataChanged], run on [scope] for each [PendingAvatarEvent] extracted from
   * a [DataEventBuffer] — touches only [context] and [WearAvatarCache], never the original
   * [DataEvent]/[DataEventBuffer] the event came from. A null [PendingAvatarEvent.asset] means either
   * a [DataEvent.TYPE_DELETED] event, so the cache entry is simply removed.
   */
  private suspend fun applyAvatarEvent(context: Context, event: PendingAvatarEvent) {
    val asset = event.asset
    if (asset == null) {
      WearAvatarCache.remove(event.threadId)
      return
    }

    val bitmap = decodeAvatarAsset(context, asset)
    if (bitmap != null) {
      WearAvatarCache.put(event.threadId, bitmap)
    } else {
      WearAvatarCache.remove(event.threadId)
    }
  }

  /**
   * Fetches [asset]'s bytes via [com.google.android.gms.wearable.DataClient.getFdForAsset] and
   * decodes them into an [ImageBitmap]. Returns null (rather than throwing) on any failure — a
   * missing/corrupt asset falls back to the colored-initials avatar via [applyAvatarEvent] rather
   * than crashing the listener service.
   */
  private suspend fun decodeAvatarAsset(context: Context, asset: Asset): ImageBitmap? {
    return try {
      val response = Wearable.getDataClient(context).getFdForAsset(asset).await()
      response.inputStream.use { input -> BitmapFactory.decodeStream(input)?.asImageBitmap() }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.w(TAG, "Failed to decode avatar asset", e)
      null
    }
  }

  /** Buffer-independent snapshot of a [WearBridgeProtocol.PATH_AVATAR] [DataEvent]; see [toPendingAvatarEvent]. */
  private data class PendingAvatarEvent(val threadId: Long, val asset: Asset?)

  companion object {
    private val TAG = Log.tag(WearMessageListenerService::class.java)

    /**
     * How long [onCapabilityChanged] waits, after seeing zero reachable bridge nodes, before
     * re-checking reachability and (only if still zero) wiping [dao]. Long enough to ride out an
     * ordinary Bluetooth blip or the phone briefly in Doze without ever confirming as an unpair;
     * chosen as a fixed value rather than exposed as a setting, since M2 has no UI for it.
     */
    @VisibleForTesting
    internal const val UNPAIR_CONFIRM_MS = 30_000L

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
     *   the unpair case. [WearAvatarCache] is cleared alongside it (Milestone 4 Task D (WEAR-004)
     *   fix) — otherwise a cached contact face photo keeps rendering after the wipe, until the
     *   process restarts.
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
          WearAvatarCache.clear()
        }
      }
    }

    /**
     * Pure decision core used twice by [onCapabilityChanged]: once, immediately, to decide whether
     * a zero-reachable-nodes callback is even worth starting the [UNPAIR_CONFIRM_MS] confirm timer
     * for; and again, after the delay, to decide whether the re-checked reachable-node count still
     * means "wipe". Both calls reduce to the same rule: wipe only when the capability that changed
     * is the Wear bridge's own ([WearBridgeProtocol.CAPABILITY]) and no node advertising it is
     * reachable. Extracted so it can be unit tested without constructing a real [CapabilityInfo],
     * which requires GmsCore.
     */
    @VisibleForTesting
    internal fun shouldWipeForCapabilityChange(capabilityName: String, reachableNodeCount: Int): Boolean {
      return capabilityName == WearBridgeProtocol.CAPABILITY && reachableNodeCount == 0
    }

    /**
     * The [com.google.android.gms.wearable.DataMap] key the phone-side `WearAvatarPublisher`
     * (`org.thoughtcrime.securesms.wear.WearAvatarPublisher`, `:app`) puts the photo [Asset] under.
     * Must match that object's `KEY_AVATAR` exactly.
     */
    private const val ASSET_KEY_AVATAR = "avatar"

    /**
     * Parses the thread id out of a [DataEvent]'s URI path for [WearBridgeProtocol.PATH_AVATAR]
     * items — the inverse of the phone-side `WearAvatarPublisher.avatarDataItemPath` (e.g.
     * `"/wear-bridge/avatar/42"` -> `42L`). Pure and unit tested directly; the one part of
     * [onDataChanged]'s handling that doesn't need GmsCore. Returns null for anything that isn't
     * exactly [WearBridgeProtocol.PATH_AVATAR] plus a slash and a valid [Long] — no path at all, a
     * different path, a missing/blank/non-numeric id, or trailing garbage after the id.
     */
    @VisibleForTesting
    internal fun threadIdFromAvatarPath(path: String): Long? {
      val prefix = "${WearBridgeProtocol.PATH_AVATAR}/"
      if (!path.startsWith(prefix)) {
        return null
      }
      return path.substring(prefix.length).toLongOrNull()
    }

    private fun ConversationDto.toEntity(): WearConversationEntity = WearConversationEntity(
      threadId = threadId,
      title = title,
      lastBody = lastBody,
      timestamp = timestamp,
      unread = unread,
      avatarColor = avatarColor,
      initials = initials
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
