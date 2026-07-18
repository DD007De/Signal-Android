package org.thoughtcrime.securesms.wear.data

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
import org.thoughtcrime.securesms.wear.bridge.WearDataClient
import org.thoughtcrime.securesms.wear.data.db.WearCacheDatabase
import org.thoughtcrime.securesms.wear.data.db.WearConversationDao
import org.thoughtcrime.securesms.wear.data.db.WearConversationEntity

/**
 * Verifies [WearConversationRepository.conversations]'s entity-to-DTO mapping against an
 * in-memory Room database (mirrors
 * [org.thoughtcrime.securesms.wear.data.db.WearConversationDaoTest]'s setup). [WearDataClient]'s
 * send methods are GmsCore-dependent and not exercised here (see [WearDataClient]'s kdoc); this
 * class only needs an instance to satisfy the constructor.
 */
@RunWith(RobolectricTestRunner::class)
class WearConversationRepositoryTest {

  private lateinit var database: WearCacheDatabase
  private lateinit var dao: WearConversationDao
  private lateinit var repository: WearConversationRepository

  @Before
  fun setUp() {
    database = Room.inMemoryDatabaseBuilder(
      ApplicationProvider.getApplicationContext(),
      WearCacheDatabase::class.java
    ).allowMainThreadQueries().build()
    dao = database.wearConversationDao()
    repository = WearConversationRepository(dao, WearDataClient(ApplicationProvider.getApplicationContext()))
  }

  @After
  fun tearDown() {
    database.close()
  }

  @Test
  fun `conversations maps cached entities back to ConversationDto`() = runTest {
    dao.upsertAll(
      listOf(
        WearConversationEntity(threadId = 2L, title = "Bob", lastBody = "hey", timestamp = 200L, unread = 0),
        WearConversationEntity(threadId = 1L, title = "Alice", lastBody = "hi", timestamp = 100L, unread = 3)
      )
    )

    val result = repository.conversations().first()

    assertEquals(
      listOf(
        ConversationDto(threadId = 2L, title = "Bob", lastBody = "hey", timestamp = 200L, unread = 0),
        ConversationDto(threadId = 1L, title = "Alice", lastBody = "hi", timestamp = 100L, unread = 3)
      ),
      result
    )
  }

  @Test
  fun `conversations maps avatarColor and initials from the cached entity`() = runTest {
    dao.upsertAll(
      listOf(
        WearConversationEntity(threadId = 1L, title = "Alice", lastBody = "hi", timestamp = 100L, unread = 0, avatarColor = -0xffff01, initials = "A")
      )
    )

    val result = repository.conversations().first()

    assertEquals(
      listOf(ConversationDto(threadId = 1L, title = "Alice", lastBody = "hi", timestamp = 100L, unread = 0, avatarColor = -0xffff01, initials = "A")),
      result
    )
  }
}
