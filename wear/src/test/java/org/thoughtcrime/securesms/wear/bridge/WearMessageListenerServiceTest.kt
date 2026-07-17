package org.thoughtcrime.securesms.wear.bridge

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.signal.core.util.wear.ConversationDto
import org.signal.core.util.wear.ConversationsPayload
import org.signal.core.util.wear.MessageDto
import org.signal.core.util.wear.MessagesPayload
import org.signal.core.util.wear.WearBridgeProtocol
import org.thoughtcrime.securesms.wear.data.db.WearCacheDatabase
import org.thoughtcrime.securesms.wear.data.db.WearConversationDao
import org.thoughtcrime.securesms.wear.data.db.WearConversationEntity

/**
 * Verifies [WearMessageListenerService.handleIncoming]'s Milestone 2 push handling (including the
 * WEAR-002 Task 9 privacy-hardening [WearBridgeProtocol.PATH_WIPE] path) and the pure
 * [WearMessageListenerService.shouldWipeForCapabilityChange] decision core, without a real
 * [WearableListenerService][com.google.android.gms.wearable.WearableListenerService] or GmsCore:
 * an in-memory Room database stands in for the watch cache (mirrors
 * [org.thoughtcrime.securesms.wear.data.db.WearConversationDaoTest]'s setup), and the `onMessages`
 * callback is a plain lambda that records what it was called with. The M1 pong path, and the real
 * [WearMessageListenerService.onCapabilityChanged] override (which needs a real [CapabilityInfo][
 * com.google.android.gms.wearable.CapabilityInfo] from GmsCore), are both handled directly rather
 * than through [handleIncoming]/[WearMessageListenerService.shouldWipeForCapabilityChange], so
 * they aren't covered here — see the class KDoc on [WearMessageListenerService] for why that's an
 * accepted, device-verified gap.
 */
@RunWith(RobolectricTestRunner::class)
class WearMessageListenerServiceTest {

  private lateinit var database: WearCacheDatabase
  private lateinit var dao: WearConversationDao

  @Before
  fun setUp() {
    database = Room.inMemoryDatabaseBuilder(
      ApplicationProvider.getApplicationContext(),
      WearCacheDatabase::class.java
    ).allowMainThreadQueries().build()
    dao = database.wearConversationDao()
  }

  @After
  fun tearDown() {
    database.close()
  }

  @Test
  fun `PATH_CONVERSATIONS writes decoded conversations to the DAO with full-replace semantics`() = runTest {
    dao.upsertAll(listOf(WearConversationEntity(threadId = 99L, title = "Stale", lastBody = "old", timestamp = 1L, unread = 0)))

    val firstPayload = ConversationsPayload(
      conversations = listOf(
        ConversationDto(threadId = 1L, title = "Alice", lastBody = "hi", timestamp = 100L, unread = 1),
        ConversationDto(threadId = 2L, title = "Bob", lastBody = "hey", timestamp = 200L, unread = 0)
      )
    )
    WearMessageListenerService.handleIncoming(
      path = WearBridgeProtocol.PATH_CONVERSATIONS,
      data = WearBridgeProtocol.encode(firstPayload),
      dao = dao,
      onMessages = { throw AssertionError("PATH_CONVERSATIONS should not invoke onMessages") }
    )

    assertEquals(setOf(1L, 2L), dao.observeAll().first().map { it.threadId }.toSet())

    // A second payload that drops threadId 1 should leave only the new set — the stale row from
    // setUp and the dropped thread must both be gone.
    val secondPayload = ConversationsPayload(
      conversations = listOf(ConversationDto(threadId = 2L, title = "Bob", lastBody = "yo", timestamp = 250L, unread = 3))
    )
    WearMessageListenerService.handleIncoming(
      path = WearBridgeProtocol.PATH_CONVERSATIONS,
      data = WearBridgeProtocol.encode(secondPayload),
      dao = dao,
      onMessages = { throw AssertionError("PATH_CONVERSATIONS should not invoke onMessages") }
    )

    val rows = dao.observeAll().first()
    assertEquals(1, rows.size)
    assertEquals(2L, rows.single().threadId)
    assertEquals("yo", rows.single().lastBody)
    assertEquals(3, rows.single().unread)
  }

  @Test
  fun `PATH_CONVERSATIONS persists avatarColor and initials onto the cached entity`() = runTest {
    val payload = ConversationsPayload(
      conversations = listOf(
        ConversationDto(threadId = 1L, title = "Alice", lastBody = "hi", timestamp = 100L, unread = 1, avatarColor = -0xffff01, initials = "A"),
        ConversationDto(threadId = 2L, title = "Bob", lastBody = "hey", timestamp = 200L, unread = 0, avatarColor = 0, initials = "")
      )
    )

    WearMessageListenerService.handleIncoming(
      path = WearBridgeProtocol.PATH_CONVERSATIONS,
      data = WearBridgeProtocol.encode(payload),
      dao = dao,
      onMessages = { throw AssertionError("PATH_CONVERSATIONS should not invoke onMessages") }
    )

    val rows = dao.observeAll().first().associateBy { it.threadId }
    assertEquals(-0xffff01, rows.getValue(1L).avatarColor)
    assertEquals("A", rows.getValue(1L).initials)
    assertEquals(0, rows.getValue(2L).avatarColor)
    assertEquals("", rows.getValue(2L).initials)
  }

  @Test
  fun `PATH_MESSAGES forwards the decoded payload to onMessages without touching the DAO`() = runTest {
    dao.upsertAll(listOf(WearConversationEntity(threadId = 1L, title = "Alice", lastBody = "hi", timestamp = 100L, unread = 0)))

    val payload = MessagesPayload(
      threadId = 5L,
      messages = listOf(MessageDto(author = "Alice", body = "hi there", timestamp = 100L, outgoing = false))
    )
    var captured: MessagesPayload? = null

    WearMessageListenerService.handleIncoming(
      path = WearBridgeProtocol.PATH_MESSAGES,
      data = WearBridgeProtocol.encode(payload),
      dao = dao,
      onMessages = { captured = it }
    )

    assertEquals(payload, captured)
    // The conversation cache seeded above must be untouched — messages are never persisted to Room.
    assertEquals(1, dao.observeAll().first().size)
  }

  @Test
  fun `PATH_WIPE clears a seeded cache without touching onMessages`() = runTest {
    dao.upsertAll(
      listOf(
        WearConversationEntity(threadId = 1L, title = "Alice", lastBody = "hi", timestamp = 100L, unread = 1),
        WearConversationEntity(threadId = 2L, title = "Bob", lastBody = "hey", timestamp = 200L, unread = 0)
      )
    )
    assertEquals(2, dao.observeAll().first().size)

    WearMessageListenerService.handleIncoming(
      path = WearBridgeProtocol.PATH_WIPE,
      data = ByteArray(0),
      dao = dao,
      onMessages = { throw AssertionError("PATH_WIPE should not invoke onMessages") }
    )

    assertEquals(0, dao.observeAll().first().size)
  }

  @Test
  fun `shouldWipeForCapabilityChange is true only for the bridge capability with zero reachable nodes`() {
    assertTrue(WearMessageListenerService.shouldWipeForCapabilityChange(WearBridgeProtocol.CAPABILITY, 0))
    assertFalse(WearMessageListenerService.shouldWipeForCapabilityChange(WearBridgeProtocol.CAPABILITY, 1))
    assertFalse(WearMessageListenerService.shouldWipeForCapabilityChange("some_other_capability", 0))
    assertFalse(WearMessageListenerService.shouldWipeForCapabilityChange("some_other_capability", 1))
  }
}
