package org.thoughtcrime.securesms.wear.data

import androidx.compose.ui.graphics.ImageBitmap
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Verifies [WearAvatarCache]'s put/remove/observe surface, keyed by thread id. [WearMessageListenerService.onDataChanged][
 * org.thoughtcrime.securesms.wear.bridge.WearMessageListenerService] (the GmsCore-dependent code
 * that actually populates this cache on-device) is not exercised here — see that class's KDoc for
 * why it's device-verified only. Runs under Robolectric because constructing an [ImageBitmap]
 * requires a real `android.graphics.Bitmap`.
 *
 * [WearAvatarCache] is a process-wide singleton, so every test clears it in [tearDown] to avoid
 * leaking state into other tests.
 */
@RunWith(RobolectricTestRunner::class)
class WearAvatarCacheTest {

  @After
  fun tearDown() {
    WearAvatarCache.clear()
  }

  @Test
  fun `get returns null for a thread id with nothing cached`() {
    assertNull(WearAvatarCache.get(1L))
  }

  @Test
  fun `put then get returns the cached bitmap for that thread id`() {
    val bitmap = ImageBitmap(4, 4)

    WearAvatarCache.put(1L, bitmap)

    assertEquals(bitmap, WearAvatarCache.get(1L))
  }

  @Test
  fun `put keys entries independently per thread id`() {
    val bitmapOne = ImageBitmap(4, 4)
    val bitmapTwo = ImageBitmap(8, 8)

    WearAvatarCache.put(1L, bitmapOne)
    WearAvatarCache.put(2L, bitmapTwo)

    assertEquals(bitmapOne, WearAvatarCache.get(1L))
    assertEquals(bitmapTwo, WearAvatarCache.get(2L))
  }

  @Test
  fun `put overwrites a previously cached bitmap for the same thread id`() {
    val original = ImageBitmap(4, 4)
    val replacement = ImageBitmap(8, 8)

    WearAvatarCache.put(1L, original)
    WearAvatarCache.put(1L, replacement)

    assertEquals(replacement, WearAvatarCache.get(1L))
  }

  @Test
  fun `remove clears only the targeted thread id`() {
    WearAvatarCache.put(1L, ImageBitmap(4, 4))
    WearAvatarCache.put(2L, ImageBitmap(4, 4))

    WearAvatarCache.remove(1L)

    assertNull(WearAvatarCache.get(1L))
    assertEquals(1, WearAvatarCache.map.size)
  }

  @Test
  fun `remove on a thread id with nothing cached is a no-op`() {
    WearAvatarCache.remove(99L)

    assertNull(WearAvatarCache.get(99L))
  }

  @Test
  fun `map reflects put and remove`() {
    val bitmap = ImageBitmap(4, 4)

    assertEquals(emptyMap<Long, ImageBitmap>(), WearAvatarCache.map)

    WearAvatarCache.put(5L, bitmap)
    assertEquals(mapOf(5L to bitmap), WearAvatarCache.map)

    WearAvatarCache.remove(5L)
    assertEquals(emptyMap<Long, ImageBitmap>(), WearAvatarCache.map)
  }

  @Test
  fun `clear empties every cached entry regardless of thread id`() {
    WearAvatarCache.put(1L, ImageBitmap(4, 4))
    WearAvatarCache.put(2L, ImageBitmap(8, 8))
    WearAvatarCache.put(3L, ImageBitmap(16, 16))

    WearAvatarCache.clear()

    assertEquals(emptyMap<Long, ImageBitmap>(), WearAvatarCache.map)
    assertNull(WearAvatarCache.get(1L))
    assertNull(WearAvatarCache.get(2L))
    assertNull(WearAvatarCache.get(3L))
  }
}
