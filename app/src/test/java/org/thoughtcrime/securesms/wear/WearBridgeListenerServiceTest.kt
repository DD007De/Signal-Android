/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.wear

import android.app.Application
import android.content.Intent
import androidx.core.app.RemoteInput
import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import io.mockk.every
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.util.getParcelableExtraCompat
import org.signal.core.util.wear.ConversationsPayload
import org.signal.core.util.wear.MessagesPayload
import org.signal.core.util.wear.MuteRequest
import org.signal.core.util.wear.ReplyRequest
import org.signal.core.util.wear.WearBridgeProtocol
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.notifications.RemoteReplyReceiver
import org.thoughtcrime.securesms.notifications.v2.DefaultMessageNotifier
import org.thoughtcrime.securesms.preferences.widgets.NotificationPrivacyPreference
import org.thoughtcrime.securesms.recipients.RecipientId
import org.thoughtcrime.securesms.testutil.RecipientTestRule
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Verifies [WearBridgeListenerService.handleMessage]'s Milestone 2 request/response paths without
 * a real GmsCore Wearable Data Layer: a fake [WearBridgeListenerService.WearResponder] captures
 * the outgoing (nodeId, path, bytes) so it can be decoded and asserted against data seeded
 * directly into the database (mirrors [WearBridgeRepositoryTest]'s setup). Also covers the M1
 * ping/pong smoke test, which is kept as-is.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class WearBridgeListenerServiceTest {

  @get:Rule
  val recipients = RecipientTestRule()

  @Test
  fun ping_stillSendsEmptyPongToSourceNode() {
    val (nodeId, path, bytes) = captureResponse { responder ->
      WearBridgeListenerService.handleMessage(
        context = context(),
        path = WearBridgeProtocol.PATH_PING,
        data = ByteArray(0),
        sourceNodeId = "watch-node",
        responder = responder
      )
    }

    assertThat(nodeId).isEqualTo("watch-node")
    assertThat(path).isEqualTo(WearBridgeProtocol.PATH_PONG)
    assertThat(bytes.toList()).isEmpty()
  }

  @Test
  fun requestConversations_sendsEncodedConversationsPayloadBackToSourceNode() {
    setPrivacy("all")

    val senderId = recipients.createRecipient("Alice Anderson")
    recipients.insertIncomingMessage(senderId)

    val (nodeId, path, bytes) = captureResponse { responder ->
      WearBridgeListenerService.handleMessage(
        context = context(),
        path = WearBridgeProtocol.PATH_REQUEST_CONVERSATIONS,
        data = ByteArray(0),
        sourceNodeId = "watch-node",
        responder = responder
      )
    }

    assertThat(nodeId).isEqualTo("watch-node")
    assertThat(path).isEqualTo(WearBridgeProtocol.PATH_CONVERSATIONS)

    val payload = WearBridgeProtocol.decode<ConversationsPayload>(bytes)
    assertThat(payload.conversations).hasSize(1)
    assertThat(payload.conversations[0].title).isEqualTo("Alice Anderson")
    assertThat(payload.conversations[0].lastBody).isEqualTo("hi")
  }

  @Test
  fun requestMessages_decodesThreadIdFromDataAndSendsEncodedMessagesPayload() {
    setPrivacy("all")

    val senderId = recipients.createRecipient("Bob Brown")
    recipients.insertIncomingMessage(senderId)
    val threadId = SignalDatabase.threads.getOrCreateThreadIdFor(senderId, false)

    val (nodeId, path, bytes) = captureResponse { responder ->
      WearBridgeListenerService.handleMessage(
        context = context(),
        path = WearBridgeProtocol.PATH_REQUEST_MESSAGES,
        data = threadId.toString().encodeToByteArray(),
        sourceNodeId = "watch-node",
        responder = responder
      )
    }

    assertThat(nodeId).isEqualTo("watch-node")
    assertThat(path).isEqualTo(WearBridgeProtocol.PATH_MESSAGES)

    val payload = WearBridgeProtocol.decode<MessagesPayload>(bytes)
    assertThat(payload.threadId).isEqualTo(threadId)
    assertThat(payload.messages).hasSize(1)
    assertThat(payload.messages[0].author).isEqualTo("Bob Brown")
    assertThat(payload.messages[0].body).isEqualTo("hi")
  }

  @Test
  fun requestMessages_malformedThreadId_doesNotRespondOrCrash() {
    assertNoResponse { responder ->
      WearBridgeListenerService.handleMessage(
        context = context(),
        path = WearBridgeProtocol.PATH_REQUEST_MESSAGES,
        data = "not-a-number".encodeToByteArray(),
        sourceNodeId = "watch-node",
        responder = responder
      )
    }
  }

  @Test
  fun requestMessages_emptyThreadId_doesNotRespondOrCrash() {
    assertNoResponse { responder ->
      WearBridgeListenerService.handleMessage(
        context = context(),
        path = WearBridgeProtocol.PATH_REQUEST_MESSAGES,
        data = ByteArray(0),
        sourceNodeId = "watch-node",
        responder = responder
      )
    }
  }

  @Test
  fun sendReply_dispatchesRemoteReplyReceiverIntentWithRecipientAndBody() {
    val senderId = recipients.createRecipient("Carol Carlson")
    val threadId = SignalDatabase.threads.getOrCreateThreadIdFor(senderId, false)

    val intent = captureDispatchedReply { dispatch ->
      WearBridgeListenerService.handleMessage(
        context = context(),
        path = WearBridgeProtocol.PATH_SEND_REPLY,
        data = WearBridgeProtocol.encode(ReplyRequest(threadId = threadId, body = "on my way")),
        sourceNodeId = "watch-node",
        responder = neverInvokedResponder(),
        dispatchReply = dispatch
      )
    }

    assertThat(intent.action).isEqualTo(RemoteReplyReceiver.REPLY_ACTION)
    assertThat(intent.getParcelableExtraCompat(RemoteReplyReceiver.RECIPIENT_EXTRA, RecipientId::class.java)).isEqualTo(senderId)

    val remoteInputResults = RemoteInput.getResultsFromIntent(intent)
    assertThat(remoteInputResults?.getCharSequence(DefaultMessageNotifier.EXTRA_REMOTE_REPLY)?.toString()).isEqualTo("on my way")
  }

  @Test
  fun sendReply_blankBody_doesNotDispatch() {
    val senderId = recipients.createRecipient("Dana Diaz")
    val threadId = SignalDatabase.threads.getOrCreateThreadIdFor(senderId, false)

    assertNoDispatch { dispatch ->
      WearBridgeListenerService.handleMessage(
        context = context(),
        path = WearBridgeProtocol.PATH_SEND_REPLY,
        data = WearBridgeProtocol.encode(ReplyRequest(threadId = threadId, body = "   ")),
        sourceNodeId = "watch-node",
        responder = neverInvokedResponder(),
        dispatchReply = dispatch
      )
    }
  }

  @Test
  fun sendReply_malformedData_doesNotDispatchOrCrash() {
    assertNoDispatch { dispatch ->
      WearBridgeListenerService.handleMessage(
        context = context(),
        path = WearBridgeProtocol.PATH_SEND_REPLY,
        data = "not-json".encodeToByteArray(),
        sourceNodeId = "watch-node",
        responder = neverInvokedResponder(),
        dispatchReply = dispatch
      )
    }
  }

  @Test
  fun sendReply_unknownThreadId_doesNotDispatch() {
    assertNoDispatch { dispatch ->
      WearBridgeListenerService.handleMessage(
        context = context(),
        path = WearBridgeProtocol.PATH_SEND_REPLY,
        data = WearBridgeProtocol.encode(ReplyRequest(threadId = 999_999L, body = "hello")),
        sourceNodeId = "watch-node",
        responder = neverInvokedResponder(),
        dispatchReply = dispatch
      )
    }
  }

  @Test
  fun markRead_marksSeededUnreadThreadRead() {
    val senderId = recipients.createRecipient("Erin Evans")
    recipients.insertIncomingMessage(senderId)
    val threadId = SignalDatabase.threads.getOrCreateThreadIdFor(senderId, false)

    assertThat(SignalDatabase.threads.getThreadRecord(threadId)?.unreadCount).isEqualTo(1)

    WearBridgeListenerService.handleMessage(
      context = context(),
      path = WearBridgeProtocol.PATH_MARK_READ,
      data = threadId.toString().encodeToByteArray(),
      sourceNodeId = "watch-node",
      responder = neverInvokedResponder()
    )

    awaitCondition { SignalDatabase.threads.getThreadRecord(threadId)?.unreadCount == 0 }
  }

  @Test
  fun markRead_malformedThreadId_doesNotMutateOrCrash() {
    val senderId = recipients.createRecipient("Erin Evans")
    recipients.insertIncomingMessage(senderId)
    val threadId = SignalDatabase.threads.getOrCreateThreadIdFor(senderId, false)

    WearBridgeListenerService.handleMessage(
      context = context(),
      path = WearBridgeProtocol.PATH_MARK_READ,
      data = "not-a-number".encodeToByteArray(),
      sourceNodeId = "watch-node",
      responder = neverInvokedResponder()
    )

    // No positive signal to await on malformed input, so give the SignalExecutors.BOUNDED work a
    // window to (mis)behave, then assert the thread is still unread -- proves the malformed
    // threadId was logged-and-returned rather than crashing or falling through to a mutation.
    Thread.sleep(500)
    assertThat(SignalDatabase.threads.getThreadRecord(threadId)?.unreadCount).isEqualTo(1)
  }

  @Test
  fun mute_setsRecipientMuteUntilForSeededThread() {
    val senderId = recipients.createRecipient("Frank Foster")
    val threadId = SignalDatabase.threads.getOrCreateThreadIdFor(senderId, false)
    val muteUntil = 1_700_000_000_000L

    assertThat(SignalDatabase.recipients.getRecord(senderId).muteUntil).isEqualTo(0L)

    WearBridgeListenerService.handleMessage(
      context = context(),
      path = WearBridgeProtocol.PATH_MUTE,
      data = WearBridgeProtocol.encode(MuteRequest(threadId = threadId, muteUntil = muteUntil)),
      sourceNodeId = "watch-node",
      responder = neverInvokedResponder()
    )

    awaitCondition { SignalDatabase.recipients.getRecord(senderId).muteUntil == muteUntil }
  }

  @Test
  fun mute_malformedData_doesNotMutateOrCrash() {
    val senderId = recipients.createRecipient("Gina Grant")
    SignalDatabase.threads.getOrCreateThreadIdFor(senderId, false)

    WearBridgeListenerService.handleMessage(
      context = context(),
      path = WearBridgeProtocol.PATH_MUTE,
      data = "not-json".encodeToByteArray(),
      sourceNodeId = "watch-node",
      responder = neverInvokedResponder()
    )

    Thread.sleep(500)
    assertThat(SignalDatabase.recipients.getRecord(senderId).muteUntil).isEqualTo(0L)
  }

  @Test
  fun mute_unknownThreadId_doesNotMutateAnyRecipientOrCrash() {
    val senderId = recipients.createRecipient("Henry Hill")
    SignalDatabase.threads.getOrCreateThreadIdFor(senderId, false)

    WearBridgeListenerService.handleMessage(
      context = context(),
      path = WearBridgeProtocol.PATH_MUTE,
      data = WearBridgeProtocol.encode(MuteRequest(threadId = 999_999L, muteUntil = 1_700_000_000_000L)),
      sourceNodeId = "watch-node",
      responder = neverInvokedResponder()
    )

    // threadId 999_999 names no seeded thread, so the unrelated seeded recipient's mute state
    // must be left untouched -- proves the missing-recipient branch was logged-and-returned
    // rather than crashing or falling through to a mutation of an arbitrary recipient.
    Thread.sleep(500)
    assertThat(SignalDatabase.recipients.getRecord(senderId).muteUntil).isEqualTo(0L)
  }

  // region helpers

  private fun context() = ApplicationProvider.getApplicationContext<Application>()

  /**
   * Polls [predicate] until it's true or [timeoutMs] elapses -- used for the
   * [WearBridgeProtocol.PATH_MARK_READ] and [WearBridgeProtocol.PATH_MUTE] paths, which mutate the
   * database off-thread via `SignalExecutors.BOUNDED` but (unlike the M2 request/response and
   * reply paths) don't call back through a [WearBridgeListenerService.WearResponder] or dispatch
   * seam, so there's no single event to latch onto.
   */
  private fun awaitCondition(timeoutMs: Long = 5_000, predicate: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
      if (predicate()) return
      Thread.sleep(25)
    }
    throw AssertionError("Timed out waiting for expected condition")
  }

  private fun setPrivacy(preference: String) {
    every { recipients.signalStore.settings.messageNotificationsPrivacy } returns NotificationPrivacyPreference(preference)
  }

  /**
   * Invokes [block] with a [WearBridgeListenerService.WearResponder] that captures the first
   * outgoing send and signals completion, then waits for it — [WearBridgeListenerService.handleMessage]
   * does its DB read and send off-thread via `SignalExecutors.BOUNDED` for the M2 paths.
   */
  private fun captureResponse(block: (WearBridgeListenerService.WearResponder) -> Unit): Triple<String, String, ByteArray> {
    val latch = CountDownLatch(1)
    val captured = AtomicReference<Triple<String, String, ByteArray>>()

    block(
      WearBridgeListenerService.WearResponder { nodeId, path, bytes ->
        captured.set(Triple(nodeId, path, bytes))
        latch.countDown()
      }
    )

    assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue()
    return captured.get()
  }

  /**
   * Invokes [block] with a [WearBridgeListenerService.WearResponder] and asserts it is never
   * called within the wait window — used to verify malformed input is swallowed (logged, not
   * responded to) rather than crashing or producing a bogus response.
   */
  private fun assertNoResponse(block: (WearBridgeListenerService.WearResponder) -> Unit) {
    val latch = CountDownLatch(1)
    val captured = AtomicReference<Triple<String, String, ByteArray>>()

    block(
      WearBridgeListenerService.WearResponder { nodeId, path, bytes ->
        captured.set(Triple(nodeId, path, bytes))
        latch.countDown()
      }
    )

    assertThat(latch.await(1, TimeUnit.SECONDS)).isFalse()
    assertThat(captured.get()).isNull()
  }

  /** A [WearBridgeListenerService.WearResponder] for reply-path tests, which never respond via the responder channel. */
  private fun neverInvokedResponder(): WearBridgeListenerService.WearResponder = WearBridgeListenerService.WearResponder { _, _, _ -> throw AssertionError("PATH_SEND_REPLY should not send a WearResponder response") }

  /**
   * Invokes [block] with a `(Intent) -> Unit` dispatch seam that captures the first dispatched
   * [Intent] and signals completion, then waits for it — mirrors [captureResponse] for the
   * [WearBridgeProtocol.PATH_SEND_REPLY] path, which dispatches a [RemoteReplyReceiver] intent
   * instead of a [WearBridgeListenerService.WearResponder] send.
   */
  private fun captureDispatchedReply(block: ((Intent) -> Unit) -> Unit): Intent {
    val latch = CountDownLatch(1)
    val captured = AtomicReference<Intent>()

    block { intent ->
      captured.set(intent)
      latch.countDown()
    }

    assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue()
    return captured.get()
  }

  /**
   * Invokes [block] with a dispatch seam and asserts it is never called within the wait window —
   * used to verify blank/malformed/unknown-thread replies are swallowed rather than dispatched.
   */
  private fun assertNoDispatch(block: ((Intent) -> Unit) -> Unit) {
    val latch = CountDownLatch(1)
    val captured = AtomicReference<Intent>()

    block { intent ->
      captured.set(intent)
      latch.countDown()
    }

    assertThat(latch.await(1, TimeUnit.SECONDS)).isFalse()
    assertThat(captured.get()).isNull()
  }

  // endregion
}
