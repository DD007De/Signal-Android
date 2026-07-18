/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.wear

import org.signal.core.util.wear.ConversationDto
import org.signal.core.util.wear.ConversationsPayload
import org.signal.core.util.wear.MessageDto
import org.signal.core.util.wear.MessagesPayload

/**
 * Fixed, in-memory demo data for the Wear bridge (WEAR-002 test aid).
 *
 * Exists purely so a **debug** build with an empty local database (e.g. a fresh emulator/device
 * that hasn't registered an account, or was just wiped) still shows something on the watch for a
 * turnkey on-device test, instead of an empty conversation list. Consumed by
 * [WearBridgeRepository] — see its `BuildConfig.DEBUG` guard — never touches the database or
 * [org.thoughtcrime.securesms.recipients.Recipient].
 *
 * Not wired into any release build: callers are expected to gate on `BuildConfig.DEBUG` and only
 * fall back to this object when the real query result was empty, so a real account with real
 * conversations is never shadowed by this fixture data.
 */
object WearDemoData {

  private const val THREAD_ALEX = 1_000_001L
  private const val THREAD_TEAM_SIGNAL = 1_000_002L
  private const val THREAD_MOM = 1_000_003L

  private val demoConversations = listOf(
    ConversationDto(
      threadId = THREAD_ALEX,
      title = "Alex",
      lastBody = "See you at 7?",
      timestamp = 1_700_000_600_000L,
      unread = 1
    ),
    ConversationDto(
      threadId = THREAD_TEAM_SIGNAL,
      title = "Team Signal",
      lastBody = "Standup moved to 10am tomorrow",
      timestamp = 1_700_000_500_000L,
      unread = 3
    ),
    ConversationDto(
      threadId = THREAD_MOM,
      title = "Mom",
      lastBody = "Don't forget to call grandma!",
      timestamp = 1_700_000_400_000L,
      unread = 0
    )
  )

  private val demoMessagesByThread: Map<Long, List<MessageDto>> = mapOf(
    THREAD_ALEX to listOf(
      MessageDto(author = "Alex", body = "See you at 7?", timestamp = 1_700_000_600_000L, outgoing = false),
      MessageDto(author = "You", body = "Sounds good, see you then", timestamp = 1_700_000_590_000L, outgoing = true),
      MessageDto(author = "Alex", body = "Want to grab dinner tonight?", timestamp = 1_700_000_580_000L, outgoing = false)
    ),
    THREAD_TEAM_SIGNAL to listOf(
      MessageDto(author = "Team Signal", body = "Standup moved to 10am tomorrow", timestamp = 1_700_000_500_000L, outgoing = false),
      MessageDto(author = "Team Signal", body = "PR is ready for review", timestamp = 1_700_000_490_000L, outgoing = false),
      MessageDto(author = "You", body = "Will take a look after lunch", timestamp = 1_700_000_480_000L, outgoing = true)
    ),
    THREAD_MOM to listOf(
      MessageDto(author = "Mom", body = "Don't forget to call grandma!", timestamp = 1_700_000_400_000L, outgoing = false),
      MessageDto(author = "You", body = "Will do, talk soon", timestamp = 1_700_000_390_000L, outgoing = true)
    )
  )

  /** The fixed set of demo conversations, newest first, matching [WearBridgeRepository.recentConversations]'s shape. */
  fun conversations(): ConversationsPayload = ConversationsPayload(conversations = demoConversations)

  /** True if [threadId] is one of the demo threads returned by [conversations]. */
  fun isDemoThread(threadId: Long): Boolean = demoMessagesByThread.containsKey(threadId)

  /** Demo messages for [threadId], newest first, or an empty payload if [threadId] isn't a demo thread. */
  fun demoMessages(threadId: Long): MessagesPayload = MessagesPayload(threadId = threadId, messages = demoMessagesByThread[threadId].orEmpty())
}
