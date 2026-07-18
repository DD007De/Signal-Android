/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.wear

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import org.junit.Test
import org.signal.core.util.wear.WearBridgeProtocol

/**
 * Verifies [WearAvatarPublisher]'s device-independent parts: the DataItem path builder
 * ([WearAvatarPublisher.avatarDataItemPath]) and the "should this thread publish a real photo?"
 * predicate ([WearAvatarPublisher.shouldPublishAvatarPhoto]), which takes its inputs as plain
 * booleans specifically so it can be exercised here without a database-backed [org.thoughtcrime.securesms.recipients.Recipient].
 *
 * The rest of [WearAvatarPublisher] — the Glide bitmap load ([org.thoughtcrime.securesms.util.AvatarUtil.getBitmapForNotification])
 * and the [com.google.android.gms.wearable.Wearable.getDataClient] put/delete calls — needs a real
 * GmsCore + Glide runtime and is device-verified only; no fake is substituted for it here, matching
 * [WearAvatarPublisher]'s own KDoc and the precedent set by [WearPushNotifierTest] (which doesn't
 * cover [WearPushNotifier.onNotificationRefreshed] for the same reason).
 */
class WearAvatarPublisherTest {

  @Test
  fun avatarDataItemPath_isProtocolPathPrefixedWithThreadId() {
    assertThat(WearAvatarPublisher.avatarDataItemPath(42L)).isEqualTo("${WearBridgeProtocol.PATH_AVATAR}/42")
    assertThat(WearAvatarPublisher.avatarDataItemPath(0L)).isEqualTo("${WearBridgeProtocol.PATH_AVATAR}/0")
  }

  @Test
  fun shouldPublishAvatarPhoto_requiresBothARealPhotoAndVisibleContactPrivacy() {
    assertThat(WearAvatarPublisher.shouldPublishAvatarPhoto(recipientHasRealPhoto = true, isDisplayContact = true)).isTrue()
  }

  @Test
  fun shouldPublishAvatarPhoto_falseWhenNoRealPhotoEvenIfContactPrivacyIsVisible() {
    assertThat(WearAvatarPublisher.shouldPublishAvatarPhoto(recipientHasRealPhoto = false, isDisplayContact = true)).isFalse()
  }

  @Test
  fun shouldPublishAvatarPhoto_falseWhenContactPrivacyIsHiddenEvenWithARealPhoto() {
    assertThat(WearAvatarPublisher.shouldPublishAvatarPhoto(recipientHasRealPhoto = true, isDisplayContact = false)).isFalse()
  }

  @Test
  fun shouldPublishAvatarPhoto_falseWhenNeitherHolds() {
    assertThat(WearAvatarPublisher.shouldPublishAvatarPhoto(recipientHasRealPhoto = false, isDisplayContact = false)).isFalse()
  }
}
