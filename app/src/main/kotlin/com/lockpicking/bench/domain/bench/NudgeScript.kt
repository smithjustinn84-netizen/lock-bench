package com.lockpicking.bench.domain.bench

import com.lockpicking.bench.domain.motion.CubicBezier

/**
 * A tap on a pin, played out over frames: slide the pick under it, lift it to the shear line,
 * hold a beat, and let go. A pin that drags or sticks on the way gets longer: the pick keeps
 * pushing for a while, which is the time to ease the wrench off a spool.
 */
internal class NudgeScript {
  private enum class Phase { Approach, Lift, Hold }

  /** The pin being nudged, or -1 when idle. */
  var pin = -1
    private set
  private var shear = 0f
  private var phase = Phase.Approach
  private var elapsed = 0f
  private var framesWaited = 0

  val isActive: Boolean get() = pin >= 0

  fun start(pin: Int, liftToShear: Float) {
    this.pin = pin
    shear = liftToShear
    phase = Phase.Approach
    elapsed = 0f
    framesWaited = 0
  }

  fun cancel() {
    pin = -1
  }

  /**
   * Advances by [dt] seconds and returns the lift to push the pin to this frame, or null once
   * the script has finished and the pick should let go. [short] says the pin has not reached
   * the shear line yet.
   */
  fun step(dt: Float, pickIsUnder: Boolean, short: Boolean = false): Float? {
    if (!isActive) return null
    return when (phase) {
      Phase.Approach -> {
        if (pickIsUnder || ++framesWaited > MAX_APPROACH_FRAMES) {
          phase = Phase.Lift
          elapsed = 0f
        }
        0f
      }
      Phase.Lift -> {
        elapsed += dt
        val t = (elapsed / LIFT_SECONDS).coerceAtMost(1f)
        if (t >= 1f) {
          phase = Phase.Hold
          elapsed = 0f
        }
        shear * CubicBezier.FastOutSlowIn.transform(t)
      }
      Phase.Hold -> {
        elapsed += dt
        if (elapsed >= HOLD_SECONDS && (!short || elapsed >= HOLD_MAX_SECONDS)) {
          cancel()
          null
        } else {
          shear
        }
      }
    }
  }

  private companion object {
    /** Give up waiting for the pick after this many frames and lift anyway. */
    const val MAX_APPROACH_FRAMES = 40
    const val LIFT_SECONDS = 0.170f
    const val HOLD_SECONDS = 0.110f

    /** How long the pick keeps pushing a pin that has not made it to the shear line. */
    const val HOLD_MAX_SECONDS = 1.2f
  }
}
