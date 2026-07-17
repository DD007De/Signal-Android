package org.thoughtcrime.securesms.wear.data

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.graphics.ImageBitmap

/**
 * Process-wide, observable cache of decoded real contact-photo bitmaps, keyed by thread id.
 * Milestone 4 Task D (WEAR-004): populated by
 * [org.thoughtcrime.securesms.wear.bridge.WearMessageListenerService.onDataChanged] when the phone
 * publishes/deletes a [org.signal.core.util.wear.WearBridgeProtocol.PATH_AVATAR] DataItem, mirroring
 * the process-wide state-holder pattern already used for
 * [org.thoughtcrime.securesms.wear.bridge.LastReply] (Milestone 1) and [WearMessagesSink]
 * (Milestone 2) — the listener service has no reference to a specific UI-layer instance to push
 * updates into, so this is a plain top-level `object` rather than something constructor-injected.
 *
 * Backed by a Compose [SnapshotStateMap] (rather than e.g. a [kotlinx.coroutines.flow.StateFlow])
 * so [org.thoughtcrime.securesms.wear.ui.ConversationAvatar] recomposes automatically the instant a
 * photo for its thread id arrives or is removed, just by reading [map] during composition — no flow
 * collection required in the UI layer.
 *
 * Not persisted: like [WearMessagesSink], this is intentionally in-memory only. A process restart
 * (or a fresh Milestone 2 `PATH_CONVERSATIONS` sync) does not by itself repopulate it; the next
 * avatar push from the phone does.
 */
object WearAvatarCache {
  private val avatars: SnapshotStateMap<Long, ImageBitmap> = mutableStateMapOf()

  /**
   * Read-only view of the cache for observers (e.g. [org.thoughtcrime.securesms.wear.ui.ConversationAvatar]).
   * Backed directly by the underlying [SnapshotStateMap], so indexing into it (`map[threadId]`)
   * from inside a `@Composable` is automatically observed by the Compose snapshot system.
   */
  val map: Map<Long, ImageBitmap> get() = avatars

  /** Caches [bitmap] as the current avatar photo for [threadId], replacing any previous entry. */
  fun put(threadId: Long, bitmap: ImageBitmap) {
    avatars[threadId] = bitmap
  }

  /** Removes any cached avatar photo for [threadId] (e.g. the phone deleted the DataItem). */
  fun remove(threadId: Long) {
    avatars.remove(threadId)
  }

  /** The currently cached avatar photo for [threadId], or null if none is cached. */
  fun get(threadId: Long): ImageBitmap? = avatars[threadId]

  /**
   * Test-only: clears every cached avatar. [WearAvatarCache] is a process-wide singleton, so tests
   * that populate it must reset it in `@After`/`tearDown` to avoid leaking state into other tests.
   */
  fun clear() {
    avatars.clear()
  }
}
