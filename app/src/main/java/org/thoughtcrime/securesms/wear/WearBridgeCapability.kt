package org.thoughtcrime.securesms.wear

import android.content.Context
import com.google.android.gms.wearable.Wearable
import org.signal.core.util.logging.Log
import org.signal.core.util.wear.WearBridgeProtocol

/**
 * Advertises the Wear bridge capability at runtime.
 *
 * The static `android_wear_capabilities` resource is not reliably picked up by Google Play Services
 * on every phone/watch pairing, which leaves a paired watch unable to discover this phone via
 * `getCapability(FILTER_REACHABLE)`. Registering it dynamically at app start is the reliable path.
 */
object WearBridgeCapability {
  private val TAG = Log.tag(WearBridgeCapability::class.java)

  @JvmStatic
  fun register(context: Context) {
    Wearable.getCapabilityClient(context)
      .addLocalCapability(WearBridgeProtocol.CAPABILITY)
      .addOnSuccessListener { Log.i(TAG, "Registered wear bridge capability") }
      .addOnFailureListener { Log.w(TAG, "Failed to register wear bridge capability", it) }
  }
}
