package com.lockpicking.bench.ui.bench.input

/** What a hand on the tension wrench can ask of the bench. */
interface WrenchInput {
  /** The wrench is held and pulled to [amount] of full torque, 0..1. Zero is a hand resting on it. */
  fun onWrenchPulled(amount: Float)

  /** The hand comes off: the torque goes, and every set pin with it. */
  fun onWrenchReleased()
}
