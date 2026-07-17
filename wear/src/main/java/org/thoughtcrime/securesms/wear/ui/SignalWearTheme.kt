package org.thoughtcrime.securesms.wear.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material.Colors
import androidx.wear.compose.material.MaterialTheme

/**
 * Wear OS branding (WEAR-003): applies Signal's brand blue ([SignalBlue], matching
 * `core/ui`'s `signal_light_colorPrimary`) as the [Colors.primary] for the watch UI, while
 * keeping every other [Colors] slot at its [androidx.wear.compose.material] default. Wrap
 * screen content in this instead of a bare `MaterialTheme { ... }` so buttons/chips/etc.
 * render Signal-blue rather than Wear's stock purple.
 */
private val SignalBlue = Color(0xFF2C58C3)
private val SignalBlueVariant = Color(0xFF1F3E8C)

private val SignalWearColors = Colors(
  primary = SignalBlue,
  primaryVariant = SignalBlueVariant,
  onPrimary = Color.White
)

@Composable
fun SignalWearTheme(content: @Composable () -> Unit) {
  MaterialTheme(
    colors = SignalWearColors,
    content = content
  )
}
