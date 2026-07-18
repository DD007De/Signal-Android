package org.thoughtcrime.securesms.wear

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import androidx.annotation.VisibleForTesting
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import org.signal.core.util.concurrent.SignalExecutors
import org.signal.core.util.logging.Log
import org.signal.core.util.wear.WearBridgeProtocol
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.util.AvatarUtil
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/**
 * Phone-side publisher of real contact photos to the watch (Milestone 4 Task C, WEAR-004).
 *
 * Milestone 4 Task A already sends [org.signal.core.util.wear.ConversationDto.avatarColor] and
 * [org.signal.core.util.wear.ConversationDto.initials] on every conversation push, enough for the
 * watch to draw Signal's colored-circle fallback avatar. This object adds the real photo, for
 * contacts that have one, over the Data Layer **Asset** API — [PutDataMapRequest] with an [Asset] —
 * rather than [com.google.android.gms.wearable.MessageClient], which is capped at ~100KB and isn't
 * meant for binary blobs. Threads without a real photo (or with the recipient hidden by the
 * notification-content privacy setting) have any stale avatar DataItem deleted instead, so a photo
 * that's removed (or a contact whose privacy is toggled off) disappears from the watch too, and it
 * falls back to the colored-initials avatar it already has from [org.thoughtcrime.securesms.wear.WearBridgeRepository].
 *
 * [publishAvatars] runs the whole per-thread loop on [SignalExecutors.BOUNDED] and never throws
 * back into its caller: any failure (no GmsCore, no reachable node, DB error, Glide error, etc.) is
 * logged via [Log.w] and swallowed, mirroring [WearPushNotifier]'s crash-safety posture. Each
 * thread is additionally wrapped in its own try/catch so one bad recipient/thread can't stop the
 * rest of the batch from publishing.
 *
 * ## Testing
 *
 * [avatarDataItemPath] (the DataItem path builder) and [shouldPublishAvatarPhoto] (the "does this
 * thread get a real photo?" predicate, taking its inputs as plain booleans) are pure and unit
 * tested. The Glide bitmap load ([AvatarUtil.getBitmapForNotification]) and the [Wearable.getDataClient]
 * put/delete calls require a real GmsCore + Glide runtime and are **device-verified only** — no fake
 * is substituted for them here, consistent with [WearPushNotifier]'s and
 * [WearBridgeListenerService.realResponder]'s existing GmsCore-touching code, which is also not unit
 * tested.
 */
object WearAvatarPublisher {
  private val TAG = Log.tag(WearAvatarPublisher::class.java)

  private const val AVATAR_SIZE_PX = 96
  private const val DEFAULT_QUALITY = 80
  private const val FALLBACK_QUALITY = 50
  private const val MAX_AVATAR_BYTES = 40 * 1024

  private const val KEY_AVATAR = "avatar"
  private const val KEY_HASH = "hash"
  private const val KEY_UPDATED = "updated"

  /**
   * For each id in [threadIds]: resolves the recipient, and either publishes a compressed photo
   * Asset (if the recipient has a real photo and notification-content privacy allows showing
   * contact identity) or deletes any stale avatar DataItem for that thread. Fire-and-forget from the
   * caller's perspective — dispatches to [SignalExecutors.BOUNDED] and never throws back.
   */
  fun publishAvatars(context: Context, threadIds: List<Long>) {
    SignalExecutors.BOUNDED.execute {
      try {
        threadIds.forEach { threadId -> publishOne(context, threadId) }
      } catch (e: Exception) {
        Log.w(TAG, "Failed to publish avatars to watch", e)
      }
    }
  }

  private fun publishOne(context: Context, threadId: Long) {
    try {
      val recipient = SignalDatabase.threads.getRecipientForThreadId(threadId)
      if (recipient == null) {
        Log.w(TAG, "No recipient found for threadId $threadId")
        return
      }

      val isDisplayContact = SignalStore.settings.messageNotificationsPrivacy.isDisplayContact
      val hasRealPhoto = recipientHasRealPhoto(recipient)

      if (!shouldPublishAvatarPhoto(hasRealPhoto, isDisplayContact)) {
        deleteAvatar(context, threadId)
        return
      }

      val bitmap = AvatarUtil.getBitmapForNotification(context, recipient, AVATAR_SIZE_PX)
      putAvatarIfChanged(context, threadId, compress(bitmap))
    } catch (e: Exception) {
      Log.w(TAG, "Failed to publish avatar for thread $threadId", e)
    }
  }

  /**
   * Whether [recipient] has a real, non-generated avatar image worth sending as a photo Asset.
   * [Recipient.contactPhoto] is the same source [org.thoughtcrime.securesms.util.AvatarUtil] itself
   * loads from (group avatar, system contact photo, or a profile avatar with an actual file on
   * disk) — it's null exactly when there's nothing but a generated fallback to show, which the
   * watch already draws locally from [org.signal.core.util.wear.ConversationDto.avatarColor]/
   * [org.signal.core.util.wear.ConversationDto.initials].
   */
  private fun recipientHasRealPhoto(recipient: Recipient): Boolean = recipient.contactPhoto != null

  private fun deleteAvatar(context: Context, threadId: Long) {
    Wearable.getDataClient(context).deleteDataItems(dataItemUri(threadId))
  }

  /**
   * Puts the avatar DataItem for [threadId] only if [bytes]' content hash differs from whatever is
   * already published there — an unchanged photo (e.g. a re-push triggered by an unrelated
   * conversation update) shouldn't cause a redundant Asset re-sync to the watch.
   */
  private fun putAvatarIfChanged(context: Context, threadId: Long, bytes: ByteArray) {
    val path = avatarDataItemPath(threadId)
    val hash = sha256Hex(bytes)

    val existingHash = try {
      Tasks.await(Wearable.getDataClient(context).getDataItem(dataItemUri(threadId)))
        ?.let { DataMapItem.fromDataItem(it).dataMap.getString(KEY_HASH) }
    } catch (e: Exception) {
      null
    }

    if (existingHash == hash) {
      return
    }

    val request = PutDataMapRequest.create(path)
      .apply {
        dataMap.putAsset(KEY_AVATAR, Asset.createFromBytes(bytes))
        dataMap.putString(KEY_HASH, hash)
        dataMap.putLong(KEY_UPDATED, System.currentTimeMillis())
      }
      .asPutDataRequest()
      .setUrgent()

    Wearable.getDataClient(context).putDataItem(request)
  }

  private fun dataItemUri(threadId: Long): Uri {
    return Uri.Builder()
      .scheme(PutDataRequest.WEAR_URI_SCHEME)
      .path(avatarDataItemPath(threadId))
      .build()
  }

  /**
   * Compresses [bitmap] to a small binary blob suitable for the Asset API: WebP (lossy) on API 30+,
   * JPEG below that, at [DEFAULT_QUALITY]. If that's still larger than [MAX_AVATAR_BYTES] (unlikely
   * at [AVATAR_SIZE_PX]), retries once at [FALLBACK_QUALITY] and accepts whatever that produces.
   */
  private fun compress(bitmap: Bitmap): ByteArray {
    val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.JPEG

    val bytes = compressAt(bitmap, format, DEFAULT_QUALITY)
    if (bytes.size <= MAX_AVATAR_BYTES) {
      return bytes
    }

    return compressAt(bitmap, format, FALLBACK_QUALITY)
  }

  private fun compressAt(bitmap: Bitmap, format: Bitmap.CompressFormat, quality: Int): ByteArray {
    val stream = ByteArrayOutputStream()
    bitmap.compress(format, quality, stream)
    return stream.toByteArray()
  }

  private fun sha256Hex(bytes: ByteArray): String {
    return MessageDigest.getInstance("SHA-256")
      .digest(bytes)
      .joinToString(separator = "") { "%02x".format(it) }
  }

  /**
   * The Data Layer path for [threadId]'s avatar DataItem: [WearBridgeProtocol.PATH_AVATAR] plus the
   * thread id. Pure and unit tested (unlike the rest of this object, which needs a real GmsCore
   * [Wearable.getDataClient] to exercise).
   */
  @VisibleForTesting
  internal fun avatarDataItemPath(threadId: Long): String = "${WearBridgeProtocol.PATH_AVATAR}/$threadId"

  /**
   * Pure predicate for "should this thread's real photo be sent to the watch?", taking its inputs
   * as plain booleans so the four combinations can be unit tested without constructing a
   * [Recipient]. Both must hold: the recipient needs an actual photo ([recipientHasRealPhoto]), and
   * notification-content privacy must currently allow showing contact identity — a hidden contact
   * can't leak its identity to the watch via a real photo any more than it already doesn't via
   * [org.thoughtcrime.securesms.wear.WearBridgeRepository]'s avatarColor/initials.
   */
  @VisibleForTesting
  internal fun shouldPublishAvatarPhoto(recipientHasRealPhoto: Boolean, isDisplayContact: Boolean): Boolean {
    return recipientHasRealPhoto && isDisplayContact
  }
}
