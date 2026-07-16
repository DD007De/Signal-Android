plugins {
  id("signal-sample-app")
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
}
