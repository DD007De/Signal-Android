plugins {
  id("signal-sample-app")
  id("com.google.devtools.ksp")
  alias(libs.plugins.compose.compiler)
}

android {
  namespace = "org.thoughtcrime.securesms.wear"

  defaultConfig {
    applicationId = "org.thoughtcrime.securesms.wear"
    minSdk = 30
  }
}

dependencies {
  // Wear OS spike (WEAR-001): direct coordinates for now; move to the version catalog
  // and add dependency-verification checksums before any upstream PR.
  implementation("androidx.wear.compose:compose-material:1.4.1")
  implementation("androidx.wear.compose:compose-foundation:1.4.1")
  implementation("com.google.android.gms:play-services-wearable:19.0.0")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")

  // WEAR-002 (M2 Task 6): encrypted Room cache. Direct coordinates for now, matching the
  // WEAR-001 spike convention; move to the version catalog before any upstream PR.
  implementation("androidx.room:room-runtime:2.7.1")
  implementation("androidx.room:room-ktx:2.7.1")
  ksp("androidx.room:room-compiler:2.7.1")

  // SQLCipher-backed SupportSQLiteOpenHelper.Factory for Room; version matches app/build.gradle.kts.
  implementation("net.zetetic:sqlcipher-android:4.16.0")
  implementation("androidx.sqlite:sqlite:2.6.2")

  // Encrypted storage for the SQLCipher passphrase.
  implementation("androidx.security:security-crypto:1.1.0")

  testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}
