package org.thoughtcrime.securesms.wear.bridge

/**
 * Data Layer contract shared by the phone (:app) and the watch (:wear).
 *
 * Milestone 1 duplicates these constants on both sides because the two modules do not yet share a
 * common module. They are unified into :core during Milestone 2. The values MUST stay identical to
 * the copy in :app (org.thoughtcrime.securesms.wear.WearBridgeProtocol).
 */
object WearBridgeProtocol {
  const val CAPABILITY = "signal_wear_bridge"
  const val PATH_PING = "/wear-bridge/ping"
  const val PATH_PONG = "/wear-bridge/pong"
}
