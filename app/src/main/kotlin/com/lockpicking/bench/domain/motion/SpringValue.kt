package com.lockpicking.bench.domain.motion

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/** A value chasing a target on a damped spring. Critically damped unless told otherwise. */
internal class SpringValue(
  initial: Float,
  private val stiffness: Float,
  private val damping: Float = 2f * sqrt(stiffness),
  private val epsilon: Float,
) {
  var value = initial
    private set
  var target = initial
  private var velocity = 0f

  fun snap(to: Float) {
    value = to
    target = to
    velocity = 0f
  }

  /** Returns true while still moving. */
  fun step(dt: Float): Boolean {
    if (value == target && velocity == 0f) return false
    var remaining = dt
    while (remaining > 0f) {
      val h = min(remaining, MAX_STEP)
      velocity += (stiffness * (target - value) - damping * velocity) * h
      value += velocity * h
      remaining -= h
    }
    if (abs(target - value) < epsilon && abs(velocity) < epsilon * SETTLE_VELOCITY_FACTOR) {
      value = target
      velocity = 0f
      return false
    }
    return true
  }

  private companion object {
    const val MAX_STEP = 1f / 120f

    /** At rest once the speed is this many times [epsilon] per second or less. */
    const val SETTLE_VELOCITY_FACTOR = 20f
  }
}
