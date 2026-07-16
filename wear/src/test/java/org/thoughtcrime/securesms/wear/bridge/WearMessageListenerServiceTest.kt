package org.thoughtcrime.securesms.wear.bridge

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
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
 * Verifies [WearMessageListenerService.handleIncoming]'s Milestone 2 push handling without a real
 * [WearableListenerService][com.google.android.gms.wearable.WearableListenerService] or GmsCore:
 * an in-memory Room database stands in for the watch cache (mirrors
 * [org.thoughtcrime.securesms.wear.data.db.WearConversationDaoTest]'s setup), and the `onMessages`
 * callback is a plain lambda that records what it was called with. The M1 pong path is handled
 * directly in [WearMessageListenerService.onMessageReceived], before [handleIncoming] is reached,
 * so it isn't covered here.
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
}
