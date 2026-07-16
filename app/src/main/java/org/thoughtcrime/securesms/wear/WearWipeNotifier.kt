package org.thoughtcrime.securesms.wear

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.CapabilityClient
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
 * ### Call site
 * Wired into [org.thoughtcrime.securesms.delete.DeleteAccountRepository.deleteAccount], the one
 * flow found that both deregisters the account from the server *and* wipes local device data —
 * i.e. an actual "logout". A review finding on the first cut of this pointed out that calling
 * [onLogout] as the very first thing in that flow was wrong: the subscription-cancel /
 * leave-groups / server-deletion steps that follow can each fail and early-`return` without local
 * data ever actually being wiped, and in that case the watch shouldn't be wiped either — the
 * account is still logged in on the phone. So [onLogout] is instead called immediately before
 * `clearApplicationUserData()`, i.e. only on the path where local data really is about to be
 * wiped, which still leaves the fire-and-forget [SignalExecutors.BOUNDED] send the most possible
 * time to complete before that call tears down the process at the end of that same flow.
 *
 * Two other local-only "clear all data" call sites were found during the search for a call site
 * (both call `ActivityManager.clearApplicationUserData()` directly, same as
 * `DeleteAccountRepository`, but *without* server deregistration first) and were deliberately left
 * unwired, since wiring exactly one confirmed site was preferred over guessing whether these two
 * also warrant the same hook:
 * - [org.thoughtcrime.securesms.components.settings.app.account.AccountSettingsFragment]
 *   (`deleteAllData()` / "Delete all data" in Account Settings, primary device)
 * - [org.thoughtcrime.securesms.components.settings.app.account.LinkedDeviceAccountSettingsFragment]
 *   (`OneTimeEvent.WipeData` / "Delete data on this device", linked device)
 *
 * TODO(WEAR-002): decide whether those two "clear all data" sites should also call [onLogout].
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
        wipeReachableNodes(reachableNodeIds(context), realResponder(context))
      } catch (e: Exception) {
        Log.w(TAG, "Failed to notify watch of logout wipe", e)
      }
    }
  }

  private fun reachableNodeIds(context: Context): List<String> {
    val capabilityInfo = Tasks.await(
      Wearable.getCapabilityClient(context)
        .getCapability(WearBridgeProtocol.CAPABILITY, CapabilityClient.FILTER_REACHABLE)
    )
    return capabilityInfo.nodes.map { it.id }
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
