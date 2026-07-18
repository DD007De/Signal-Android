package org.thoughtcrime.securesms.wear

/**
 * In-process record of which thread (if any) the paired watch currently has open, reported over
 * [org.signal.core.util.wear.WearBridgeProtocol.PATH_VISIBLE_THREAD]. Read by [WearPushNotifier] to
 * skip a redundant per-message notification for the thread the user is already reading on the watch
 * (it still updates inline via the WEAR-005 auto-refresh). `-1L` means no thread is open.
 *
 * Accepted lifecycle gap (matches WearMessageListenerService's precedent): if the watch app is
 * force-killed while a thread is open it can't send the "none" update, so the phone may keep
 * suppressing that one thread's buzz until the watch app is next opened and closed. Normal
 * navigation (back to list) and the Activity's onStop both clear it.
 */
object WearWatchState {
  @Volatile
  var visibleThreadId: Long = -1L

  fun set(threadId: Long) {
    visibleThreadId = threadId
  }
}
