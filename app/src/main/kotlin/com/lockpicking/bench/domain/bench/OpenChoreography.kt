package com.lockpicking.bench.domain.bench

import com.lockpicking.bench.domain.lock.LockMechanics
import com.lockpicking.bench.domain.model.LockGeometry
import com.lockpicking.bench.domain.motion.SpringValue
import kotlin.math.abs
import kotlin.math.sign

/**
 * The plug, the shackle's lift and the shackle's swing.
 *
 * Opening plays in three stages: the plug turns, then the shackle pops, then it swings.
 * Relocking plays the same stages backwards, and only then lets the drivers fall.
 */
internal class OpenChoreography(private val geometry: LockGeometry) {
  private val openSign = sign(geometry.openDegrees)

  private val plug = SpringValue(0f, stiffness = 120f, epsilon = 0.02f)
  private val shackleLift = SpringValue(0f, stiffness = 320f, damping = 15f, epsilon = 0.0003f)
  private val shackleSwing = SpringValue(0f, stiffness = 42f, epsilon = 0.05f)

  /** True from a reset on an open lock until the plug is home again. */
  var relocking = false
    private set

  val plugDegrees: Float get() = plug.value
  val shackleLiftValue: Float get() = shackleLift.value
  val shackleSwingDegrees: Float get() = shackleSwing.value

  fun beginRelock() {
    relocking = true
  }

  /** Sets this frame's targets from [lock], steps the springs, and returns true while moving. */
  fun advance(dt: Float, lock: LockMechanics): Boolean {
    val open = lock.opened && !relocking
    shackleSwing.target = if (open && shackleLift.value > geometry.shackleTravel * SWING_AT) SWING_DEGREES else 0f
    shackleLift.target = when {
      open && abs(plug.value) > LIFT_AT_PLUG_DEGREES -> geometry.shackleTravel
      !open && abs(shackleSwing.value) > SWING_HOME_DEGREES -> geometry.shackleTravel
      else -> 0f
    }
    plug.target = when {
      open -> geometry.openDegrees
      relocking -> if (shackleLift.value > SHACKLE_SEATED) geometry.openDegrees else 0f
      else -> openSign * lock.plugDegrees
    }
    if (relocking && plug.target == 0f && abs(plug.value) < PLUG_HOME_DEGREES) {
      relocking = false
      lock.relock()
    }

    var moving = plug.step(dt)
    moving = shackleLift.step(dt) or moving
    moving = shackleSwing.step(dt) or moving
    return moving
  }

  private companion object {
    /** Swings the toe out toward the keyway side, where the default camera can see it. */
    const val SWING_DEGREES = -72f

    /** Fraction of its travel the shackle must rise before it may swing. */
    const val SWING_AT = 0.9f
    const val LIFT_AT_PLUG_DEGREES = 80f
    const val SWING_HOME_DEGREES = 2f
    const val SHACKLE_SEATED = 0.004f
    const val PLUG_HOME_DEGREES = 1f
  }
}
