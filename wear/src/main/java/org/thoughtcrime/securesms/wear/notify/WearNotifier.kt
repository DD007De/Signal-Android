package org.thoughtcrime.securesms.wear.notify

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.signal.core.util.wear.NotifyDto
import org.thoughtcrime.securesms.wear.R
import org.thoughtcrime.securesms.wear.WearMainActivity

/**
 * WEAR-005: raises a real Android notification on the watch for a phone-pushed
 * [org.signal.core.util.wear.WearBridgeProtocol.PATH_NOTIFY]. The phone only fires that push for
 * threads it itself just alerted on ([org.thoughtcrime.securesms.wear.WearPushNotifier] /
 * `threadsThatAlerted`), so mute / notification-privacy / notification-profiles / notifications-off
 * are all honoured implicitly — this object never re-derives or re-applies any of that policy, it
 * only renders what the phone already decided to alert on.
 *
 * Privacy is applied on the phone before the [NotifyDto] crosses the wire (blank title/body when
 * contact/message privacy is hidden); [notificationText] is the one place the watch fills in a
 * localized generic string for a blank body, rather than ever fabricating content.
 */
object WearNotifier {
  /** Intent extra key a tapped notification's [WearMainActivity] deep-link reads to open the thread. */
  const val EXTRA_THREAD_ID = "org.thoughtcrime.securesms.wear.THREAD_ID"
  private const val CHANNEL_ID = "wear_messages"

  /** Body to show: the real message, or a localized generic string when privacy blanked it. */
  fun notificationText(body: String, genericFallback: String): String = if (body.isBlank()) genericFallback else body

  /**
   * Raises (or replaces, for the same thread) a notification for [dto]. A no-op when the user has
   * disabled notifications for the app — [NotificationManagerCompat.areNotificationsEnabled] guards
   * the actual [NotificationManagerCompat.notify] call, which is what makes the
   * `POST_NOTIFICATIONS` runtime permission (API 33+) safe to skip requesting synchronously here.
   */
  @SuppressLint("MissingPermission")
  fun notify(context: Context, dto: NotifyDto) {
    ensureChannel(context)
    val text = notificationText(dto.body, context.getString(R.string.wear_notify_generic_body))

    val tapIntent = Intent(context, WearMainActivity::class.java)
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
      .putExtra(EXTRA_THREAD_ID, dto.threadId)
    val pending = PendingIntent.getActivity(
      context,
      dto.threadId.toInt(),
      tapIntent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    val notification = NotificationCompat.Builder(context, CHANNEL_ID)
      .setSmallIcon(R.mipmap.ic_launcher)
      .setContentTitle(dto.title)
      .setContentText(text)
      .setAutoCancel(true)
      .setCategory(NotificationCompat.CATEGORY_MESSAGE)
      .setPriority(NotificationCompat.PRIORITY_HIGH)
      .setContentIntent(pending)
      .build()

    // Per-thread id so multiple threads stack and a newer message for the same thread replaces itself.
    if (NotificationManagerCompat.from(context).areNotificationsEnabled()) {
      NotificationManagerCompat.from(context).notify(dto.threadId.toInt(), notification)
    }
  }

  private fun ensureChannel(context: Context) {
    val manager = context.getSystemService(NotificationManager::class.java)
    if (manager.getNotificationChannel(CHANNEL_ID) == null) {
      manager.createNotificationChannel(
        NotificationChannel(CHANNEL_ID, context.getString(R.string.wear_notify_channel_name), NotificationManager.IMPORTANCE_HIGH)
      )
    }
  }
}
