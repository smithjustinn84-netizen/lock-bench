package com.lockpicking.bench.domain.bench

import com.lockpicking.bench.domain.lock.LockMechanics
import com.lockpicking.bench.domain.model.LockGeometry
import com.lockpicking.bench.domain.motion.SpringValue
import kotlin.math.min

/**
 * The hook pick: follows the hand along the keyway, rides the pin it is pushing, ducks under
 * the lands between chambers, and parks outside the lock when it is not in use.
 */
internal class PickMotion(private val geometry: LockGeometry, private val keyway: Keyway) {
  private val hookZ = SpringValue(geometry.pickParkedZ, stiffness = 900f, epsilon = 0.0004f)

  /** A rake is scrubbed, several strokes a second: it has to keep up with the thumb or the strokes fall short. */
  private val rakeZ = SpringValue(geometry.pickParkedZ, stiffness = 6000f, epsilon = 0.0004f)
  private var z = hookZ
  private val y = SpringValue(geometry.pickParkedY, stiffness = 320f, epsilon = 0.0004f)

  val pickZ: Float get() = z.value
  val pickY: Float get() = y.value

  /** The pin the tip is under right now, or -1: only that pin can be pushed. */
  val pinUnderTip: Int get() = keyway.pinAt(z.value)

  fun isUnder(pin: Int): Boolean = pinUnderTip == pin

  /**
   * Aims at [depth] along the keyway (or the parked spot when it is NaN) with the hand asking
   * for [lift], steps, and returns true while moving.
   */
  fun advance(dt: Float, depth: Float, lift: Float, lock: LockMechanics): Boolean {
    follow(hookZ)
    if (depth.isNaN()) {
      z.target = geometry.pickParkedZ
      y.target = geometry.pickParkedY
    } else {
      z.target = keyway.zAt(depth)
      val contact = contactY
      val held = lock.heldPin
      if (held >= 0 && held == pinUnderTip) {
        y.snap(contact + lock.keyLift(held))
      } else {
        y.target = contact + min(lift, Keyway.LAND_CLEARANCE)
      }
    }
    var moving = z.step(dt)
    moving = y.step(dt) or moving
    return moving
  }

  /** The height a pick's tip is at when it just touches a pin at rest. */
  val contactY: Float get() = geometry.pinRestY - PICK_GAP

  /**
   * A rake follows the hand along the keyway the same way, but how high it rides is settled by
   * the pins it is under, so the bench says ([ride]) once it has worked that out. Steps the
   * slide alone and returns true while it is moving.
   */
  fun slide(dt: Float, depth: Float): Boolean {
    follow(rakeZ)
    if (depth.isNaN()) {
      z.target = geometry.pickParkedZ
      y.target = geometry.pickParkedY
      val moving = z.step(dt)
      return y.step(dt) or moving
    }
    z.target = keyway.zAt(depth)
    return z.step(dt)
  }

  /** Hands the slide over to [spring] from wherever the other one had got to. */
  private fun follow(spring: SpringValue) {
    if (z === spring) return
    spring.snap(z.value)
    z = spring
  }

  /** Puts the rake's lead peak [lift] above where it touches a pin at rest. */
  fun ride(lift: Float) {
    y.snap(contactY + lift)
  }

  private companion object {
    /** Daylight between the hook tip and the pin it is about to lift. */
    const val PICK_GAP = 0.002f
  }
}
