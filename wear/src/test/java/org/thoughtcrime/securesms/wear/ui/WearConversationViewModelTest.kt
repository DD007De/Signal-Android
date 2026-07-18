package org.thoughtcrime.securesms.wear.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.signal.core.util.wear.ConversationDto
import org.signal.core.util.wear.MessagesPayload
import org.thoughtcrime.securesms.wear.data.WearConversationDataSource

/**
 * Unit tests [WearConversationViewModel] against [FakeWearConversationDataSource], a fake
 * implementation of [WearConversationDataSource] — no Room or GmsCore Data Layer client is
 * touched, unlike [org.thoughtcrime.securesms.wear.data.WearConversationRepositoryTest] which
 * covers the real repository's entity-to-DTO mapping.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class WearConversationViewModelTest {

  private val dispatcher = StandardTestDispatcher()

  @Before
  fun setUp() {
    Dispatchers.setMain(dispatcher)
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  @Test
  fun `conversations reflects the repository flow`() = runTest(dispatcher) {
    val fake = FakeWearConversationDataSource()
    val viewModel = WearConversationViewModel(fake)
    dispatcher.scheduler.advanceUntilIdle()

    assertEquals(emptyList<ConversationDto>(), viewModel.conversations.value)

    fake.conversationsFlow.value = listOf(
      ConversationDto(threadId = 1L, title = "Alice", lastBody = "hi", timestamp = 100L, unread = 1)
    )
    dispatcher.scheduler.advanceUntilIdle()

    assertEquals(
      listOf(ConversationDto(threadId = 1L, title = "Alice", lastBody = "hi", timestamp = 100L, unread = 1)),
      viewModel.conversations.value
    )
  }

  @Test
  fun `refresh calls through to the repository`() = runTest(dispatcher) {
    val fake = FakeWearConversationDataSource()
    val viewModel = WearConversationViewModel(fake)

    viewModel.refresh()
    dispatcher.scheduler.advanceUntilIdle()

    assertEquals(1, fake.refreshCalls)
  }

  @Test
  fun `open calls through to openThread`() = runTest(dispatcher) {
    val fake = FakeWearConversationDataSource()
    val viewModel = WearConversationViewModel(fake)

    viewModel.open(threadId = 9L)
    dispatcher.scheduler.advanceUntilIdle()

    assertEquals(listOf(9L), fake.openedThreadIds)
  }

  @Test
  fun `reply calls through to the repository`() = runTest(dispatcher) {
    val fake = FakeWearConversationDataSource()
    val viewModel = WearConversationViewModel(fake)

    viewModel.reply(threadId = 7L, body = "OK")
    dispatcher.scheduler.advanceUntilIdle()

    assertEquals(listOf(7L to "OK"), fake.sentReplies)
  }

  @Test
  fun `messages exposes the repository's messages state flow`() = runTest(dispatcher) {
    val fake = FakeWearConversationDataSource()
    val viewModel = WearConversationViewModel(fake)

    assertEquals(null, viewModel.messages.value)

    val payload = MessagesPayload(threadId = 9L, messages = emptyList())
    fake.messagesFlow.value = payload

    assertEquals(payload, viewModel.messages.value)
  }

  private class FakeWearConversationDataSource : WearConversationDataSource {
    val conversationsFlow = MutableStateFlow<List<ConversationDto>>(emptyList())
    val messagesFlow = MutableStateFlow<MessagesPayload?>(null)
    val openedThreadIds = mutableListOf<Long>()
    val sentReplies = mutableListOf<Pair<Long, String>>()
    var refreshCalls = 0

    override fun conversations(): Flow<List<ConversationDto>> = conversationsFlow

    override val messages: StateFlow<MessagesPayload?> = messagesFlow

    override suspend fun refresh(): Boolean {
      refreshCalls++
      return true
    }

    override suspend fun openThread(threadId: Long): Boolean {
      openedThreadIds += threadId
      return true
    }

    override suspend fun reply(threadId: Long, body: String): Boolean {
      sentReplies += threadId to body
      return true
    }
  }
}
