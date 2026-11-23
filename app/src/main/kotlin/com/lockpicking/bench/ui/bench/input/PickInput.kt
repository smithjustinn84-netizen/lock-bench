package com.lockpicking.bench.ui.bench.input

/** What a thumb on the pick pad can ask of the bench. */
interface PickInput {
  /** Where the pick is along the keyway now, 0 at the mouth to 1 at the back wall. */
  val pickDepth: Float

  /**
   * The pick is held at [depth] along the keyway with its tip lifted to [lift] of the full
   * travel, 0 at rest. A [depth] outside 0..1 is the hand pushing on the end of the keyway.
   */
  fun onPickMoved(depth: Float, lift: Float)

  /** The thumb comes off: the tip drops, and the pick stays where it is in the keyway. */
  fun onPickRested()

  /** A tap [across] the pad, 0 at its left edge to 1 at its right: nudge the pin drawn there. */
  fun onPickTapped(across: Float)
}
