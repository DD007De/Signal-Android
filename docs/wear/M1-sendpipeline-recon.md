# WEAR-001 — Send-pipeline recon (reply hook)

**Question:** how does a watch-originated reply reach Signal's existing send pipeline, and what does the watch need to supply?

## Finding: Signal already has a Wearable reply path

`app/src/main/java/org/thoughtcrime/securesms/notifications/RemoteReplyReceiver.java` is documented verbatim as *"Get the response text from the Wearable Device and sends a message as a reply"*. It is the notification quick-reply `BroadcastReceiver` (originally for the old Android Wear notification reply), and it demonstrates the exact send entry point we need.

### The send entry point

```java
OutgoingMessage reply = OutgoingMessage.quickReply(
    recipient,          // Recipient (resolved from RecipientId)
    slideDeck,          // SlideDeck? (text slide; null for plain text)
    body,               // String — the reply text (post MessageUtil.getSplitMessage)
    parentStoryId       // ParentStoryId? (null for a normal 1:1/group reply)
);

long threadId = MessageSender.send(
    context,
    reply,
    -1,                          // threadId -1 = resolve from recipient
    MessageSender.SendType.SIGNAL,
    null,                        // metricId
    null                         // completion callback
);
```

Then it marks the thread read + refreshes the notifier:
```java
SignalDatabase.threads().setRead(threadId);
AppDependencies.getMessageNotifier().updateNotification(context);
```

Key facts:
- Runs **off the main thread** (`SignalExecutors.BOUNDED`). Our bridge must do the same.
- Text is split via `MessageUtil.getSplitMessage(context, body)` before building the slide deck — reuse that.
- `RECIPIENT_EXTRA` / `REPLY_METHOD` / `GROUP_STORY_ID_EXTRA` / `EARLIEST_TIMESTAMP` are the intent extras the existing receiver expects.

## What the watch must supply (interface for Milestone 2)

The write-path in M2 needs, per reply:
- a **RecipientId** (or a thread id resolvable to one) — the watch gets this from the synced conversation list;
- the **reply text** (String);
- the **reply method** (SecureMessage vs GroupMessage) — derivable from the recipient.

## Two viable M2 implementations

1. **Reuse `RemoteReplyReceiver` as-is.** Have the phone-side `WearBridge` construct the same `Intent` (action `RemoteReplyReceiver.REPLY_ACTION`, the extras above, and a `RemoteInput` results bundle) and dispatch it. Lowest-risk: zero new send logic, rides the exact existing path.
2. **Call the send entry point directly** from `WearBridge` (the `OutgoingMessage.quickReply` + `MessageSender.send` snippet above). Cleaner, but duplicates the receiver's surrounding logic (split, mark-read, notifier refresh).

**Recommendation for M2:** start with option 1 (reuse the receiver) to prove end-to-end with the least new code, then refactor to a shared helper if a direct call is warranted.

## Milestone 1 scope note

M1 does **not** send a real message (no synced recipient yet). This recon documents the hook; the guarded proof is deferred to M2's write-path task, where a real synced RecipientId is available. Risk retired: the send entry point is a clean, existing, off-main-thread static call — no protocol or pipeline changes needed.
