package com.lockpicking.bench.ui.bench.input

/** What a pin gesture can ask of the bench. Pins are 0-based. */
interface PinInput {
  /** False while a pin touch should fall through to the camera instead, such as when the lock is open. */
  val acceptsPins: Boolean

  fun onPinPressed(pin: Int)

  /** [fraction] of the pin's full travel: 0 at rest, 1 at the top of its chamber. */
  fun onPinDragged(pin: Int, fraction: Float)

  fun onPinReleased()

  /** A tap: lift [pin] exactly to the shear line, then let go. */
  fun onPinTapped(pin: Int)
}
