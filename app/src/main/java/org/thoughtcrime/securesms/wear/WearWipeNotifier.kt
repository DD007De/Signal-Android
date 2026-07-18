package org.thoughtcrime.securesms.wear

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.google.android.gms.wearable.Wearable
import org.signal.core.util.concurrent.SignalExecutors
import org.signal.core.util.logging.Log
import org.signal.core.util.wear.WearBridgeProtocol

/**
 * Phone-side wipe path of the Wear bridge (Milestone 2, WEAR-002 Task 9 — privacy hardening).
 *
 * Sends [WearBridgeProtocol.PATH_WIPE] (empty body) to every reachable watch node so the watch's
 * encrypted conversation cache ([org.thoughtcrime.securesms.wear.data.db.WearCacheDatabase] on the
 * `:wear` side — not visible from `:app`) is wiped whenever this phone loses its Signal account.
 * Mirrors [WearPushNotifier]'s reachable-node + send pattern: [onLogout] runs entirely on
 * [SignalExecutors.BOUNDED], never blocks its caller, and never throws back into it — any failure
 * (no GmsCore, no reachable node, etc.) is logged via [Log.w] and swallowed, same rationale as
 * [WearPushNotifier.onNotificationRefreshed]: a Wear bridge problem must never interfere with the
 * (much more important) account-deletion / data-wipe flow that's calling this.
 *
 * This does *not* cover the case where the watch is unpaired/unreachable and never receives the
 * push at all — that's handled independently, watch-side, by
 * [org.thoughtcrime.securesms.wear.bridge.WearMessageListenerService.onCapabilityChanged].
 *
 * ### Call sites
 * Wired into every local flow found that ends in `ActivityManager.clearApplicationUserData()` —
 * i.e. every place this phone can lose its locally-cached Signal data, whether or not the account
 * was also deregistered from the server first:
 *
 * - [org.thoughtcrime.securesms.delete.DeleteAccountRepository.deleteAccount] — the primary
 *   "delete my account" flow, which both deregisters the account from the server *and* wipes local
 *   device data. A review finding on the first cut of this pointed out that calling [onLogout] as
 *   the very first thing in that flow was wrong: the subscription-cancel / leave-groups /
 *   server-deletion steps that follow can each fail and early-`return` without local data ever
 *   actually being wiped, and in that case the watch shouldn't be wiped either — the account is
 *   still logged in on the phone. So [onLogout] is instead called immediately before
 *   `clearApplicationUserData()`, i.e. only on the path where local data really is about to be
 *   wiped, which still leaves the fire-and-forget [SignalExecutors.BOUNDED] send the most possible
 *   time to complete before that call tears down the process at the end of that same flow.
 * - [org.thoughtcrime.securesms.components.settings.app.account.AccountSettingsFragment.deleteAllData]
 *   ("Delete all data" in Account Settings, primary device) — a local-only wipe with no server
 *   deregistration step first. [onLogout] is called immediately before its
 *   `clearApplicationUserData()` call, same placement rationale as above.
 * - [org.thoughtcrime.securesms.components.settings.app.account.LinkedDeviceAccountSettingsFragment]'s
 *   `OneTimeEvent.WipeData` handler ("Delete data on this device", linked device) — likewise a
 *   local-only wipe; [onLogout] is called immediately before its `clearApplicationUserData()` call.
 *
 * All three call sites pass a real, non-test [Context], so a failure inside [onLogout] (no GmsCore,
 * no reachable node, etc.) is caught, logged, and swallowed per the class doc above — none of them
 * can be blocked or broken by a Wear bridge problem.
 */
object WearWipeNotifier {
  private val TAG = Log.tag(WearWipeNotifier::class.java)

  /**
   * Entry point called from [org.thoughtcrime.securesms.delete.DeleteAccountRepository.deleteAccount].
   * See the class doc for why that's the confirmed call site and why it's called early in that flow.
   */
  fun onLogout(context: Context) {
    SignalExecutors.BOUNDED.execute {
      try {
        wipeReachableNodes(WearNodes.reachableOrConnected(context), realResponder(context))
      } catch (e: Exception) {
        Log.w(TAG, "Failed to notify watch of logout wipe", e)
      }
    }
  }

  private fun realResponder(context: Context): WearBridgeListenerService.WearResponder = WearBridgeListenerService.WearResponder { nodeId, path, bytes ->
    Wearable.getMessageClient(context)
      .sendMessage(nodeId, path, bytes)
      .addOnFailureListener { Log.w(TAG, "Failed to send $path to $nodeId", it) }
  }

  /**
   * Testable core of the wipe: given a set of reachable node ids and a
   * [WearBridgeListenerService.WearResponder] seam, sends an empty-body
   * [WearBridgeProtocol.PATH_WIPE] to each node.
   *
   * If [nodeIds] is empty this is a cheap no-op.
   */
  @VisibleForTesting
  fun wipeReachableNodes(nodeIds: List<String>, responder: WearBridgeListenerService.WearResponder) {
    if (nodeIds.isEmpty()) {
      return
    }

    nodeIds.forEach { nodeId ->
      responder.send(nodeId, WearBridgeProtocol.PATH_WIPE, ByteArray(0))
    }
  }
}
