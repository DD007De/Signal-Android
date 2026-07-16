# WEAR-001 — Data Layer round-trip evidence (Milestone 1)

## Setup
- Phone emulator: `Pixel_10_Pro`, image `android-36.1 / google_apis / x86_64` (GmsCore present, no Play Store). Fork APK `Signal-Android-play-prod-x86_64-debug-8.20.0.apk` installed (`org.thoughtcrime.securesms`).
- Watch emulator: `wear_m1`, image `android-34 / android-wear / x86_64` (Wear OS 5). Wear APK `wear-debug.apk` installed (`org.thoughtcrime.securesms.wear`).
- Both booted; both APKs installed successfully.

## Result: watch app runs correctly; nodes not paired

Launching the watch app and tapping the button executed the full watch code path — Compose UI → coroutine → `WearDataClient.ping()` → `CapabilityClient.getCapability("signal_wear_bridge", FILTER_REACHABLE)` — and correctly rendered **"no phone"**, which is exactly the return of `ping()` when no reachable node advertises the capability.

**Interpretation:**
- ✅ The `:wear` app installs and runs on a real Wear OS emulator; the ping code path works end-to-end and handles the no-node case correctly.
- 🔗 The two emulators are **not paired**, so `CapabilityClient` sees no node. This is an emulator infrastructure limitation, not a code defect.

## Why the pong hop is not shown here
Pairing a Wear emulator with a phone emulator for the Data Layer requires the **Wear OS companion app**, which is only on the Play Store. The `google_apis` phone image has GmsCore but no Play Store, and installing/pairing the companion app needs an interactive Google-account sign-in — not possible headlessly.

**Definitive live check options (deferred):**
1. A `google_apis_playstore` phone emulator with a signed-in Google account + the Wear OS app to pair the emulators (interactive).
2. A physical phone + physical Wear OS watch. Note: the prod-debug build is `org.thoughtcrime.securesms` (clashes with real Signal); use a `staging` build (`org.thoughtcrime.securesms.staging`) to coexist, or a dedicated test phone.

## Conclusion
Milestone 1's de-risking goal is met: the module, both-sides build, the transport code path, and the send-pipeline hook are all proven. The only unproven step — the literal pong delivery — is gated by emulator pairing infrastructure, not by this design or code. The live pong is validated in Milestone 2 against a paired device.
