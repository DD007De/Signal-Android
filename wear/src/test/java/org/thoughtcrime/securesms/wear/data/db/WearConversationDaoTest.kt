package org.thoughtcrime.securesms.wear.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Verifies [WearConversationDao] behavior against an in-memory Room database. No SQLCipher is
 * involved here; encryption is exercised only by [WearCacheDatabase.getInstance] at runtime.
 */
@RunWith(RobolectricTestRunner::class)
class WearConversationDaoTest {

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
  fun `upsertAll inserts new rows`() = runTest {
    dao.upsertAll(
      listOf(
        WearConversationEntity(threadId = 1L, title = "Alice", lastBody = "hi", timestamp = 100L, unread = 1),
        WearConversationEntity(threadId = 2L, title = "Bob", lastBody = "hey", timestamp = 200L, unread = 0)
      )
    )

    val rows = dao.observeAll().first()
    assertEquals(2, rows.size)
  }

  @Test
  fun `upsertAll replaces existing rows by threadId`() = runTest {
    dao.upsertAll(listOf(WearConversationEntity(threadId = 1L, title = "Alice", lastBody = "hi", timestamp = 100L, unread = 1)))
    dao.upsertAll(listOf(WearConversationEntity(threadId = 1L, title = "Alice", lastBody = "updated", timestamp = 150L, unread = 0)))

    val rows = dao.observeAll().first()
    assertEquals(1, rows.size)
    assertEquals("updated", rows.single().lastBody)
    assertEquals(150L, rows.single().timestamp)
    assertEquals(0, rows.single().unread)
  }

  @Test
  fun `observeAll orders by timestamp descending`() = runTest {
    dao.upsertAll(
      listOf(
        WearConversationEntity(threadId = 1L, title = "Old", lastBody = "a", timestamp = 100L, unread = 0),
        WearConversationEntity(threadId = 2L, title = "New", lastBody = "b", timestamp = 300L, unread = 0),
        WearConversationEntity(threadId = 3L, title = "Mid", lastBody = "c", timestamp = 200L, unread = 0)
      )
    )

    val rows = dao.observeAll().first()
    assertEquals(listOf(300L, 200L, 100L), rows.map { it.timestamp })
    assertEquals(listOf("New", "Mid", "Old"), rows.map { it.title })
  }

  @Test
  fun `replaceAll clears the table before inserting, so a dropped row disappears`() = runTest {
    dao.upsertAll(
      listOf(
        WearConversationEntity(threadId = 1L, title = "Alice", lastBody = "hi", timestamp = 100L, unread = 1),
        WearConversationEntity(threadId = 2L, title = "Bob", lastBody = "hey", timestamp = 200L, unread = 0)
      )
    )

    dao.replaceAll(listOf(WearConversationEntity(threadId = 2L, title = "Bob", lastBody = "yo", timestamp = 250L, unread = 3)))

    val rows = dao.observeAll().first()
    assertEquals(1, rows.size)
    assertEquals(2L, rows.single().threadId)
    assertEquals("yo", rows.single().lastBody)
    assertEquals(3, rows.single().unread)
  }

  @Test
  fun `clear empties the table`() = runTest {
    dao.upsertAll(
      listOf(
        WearConversationEntity(threadId = 1L, title = "Alice", lastBody = "hi", timestamp = 100L, unread = 1),
        WearConversationEntity(threadId = 2L, title = "Bob", lastBody = "hey", timestamp = 200L, unread = 0)
      )
    )
    assertTrue(dao.observeAll().first().isNotEmpty())

    dao.clear()

    assertTrue(dao.observeAll().first().isEmpty())
  }
}
