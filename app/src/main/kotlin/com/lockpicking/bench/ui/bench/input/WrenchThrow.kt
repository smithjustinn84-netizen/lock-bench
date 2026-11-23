package com.lockpicking.bench.ui.bench.input

import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * How far the wrench lever is pulled, against the torque that gives.
 *
 * The curve spends most of the throw on light torque, where a spool is passed or lost in a
 * few hundredths, and gets to heavy torque quickly at the end.
 */
object WrenchThrow {
  private const val CURVE = 1.5f

  /** Torque is reported in steps this fine, so a thumb at rest does not send a stream of changes. */
  private const val STEPS = 200

  /** Torque 0..1 for a lever pulled [fraction] of its throw. */
  fun torqueAt(fraction: Float): Float {
    val torque = fraction.coerceIn(0f, 1f).pow(CURVE)
    return (torque * STEPS).roundToInt() / STEPS.toFloat()
  }

  /** How far along its throw the lever sits at [torque]. */
  fun fractionAt(torque: Float): Float = torque.coerceIn(0f, 1f).pow(1f / CURVE)
}
