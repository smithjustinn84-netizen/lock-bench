package com.lockpicking.bench.domain.bench

import com.lockpicking.bench.domain.lock.LockMechanics
import com.lockpicking.bench.domain.model.LockGeometry
import com.lockpicking.bench.domain.motion.SpringValue
import kotlin.math.abs

/** The hook pick: waits under the selected pin, rides it while pushing, and parks when idle. */
internal class PickMotion(private val geometry: LockGeometry) {
  private val z = SpringValue(geometry.pickParkedZ, stiffness = 300f, epsilon = 0.0004f)
  private val y = SpringValue(geometry.pickParkedY, stiffness = 320f, epsilon = 0.0004f)

  val pickZ: Float get() = z.value
  val pickY: Float get() = y.value

  /** True once the tip has arrived under [pin]: only then does a push land on it. */
  fun isUnder(pin: Int): Boolean = abs(z.value - geometry.pinZ[pin]) < ARRIVED

  /** Aims at [selected] (or the parked spot when it is -1), steps, and returns true while moving. */
  fun advance(dt: Float, selected: Int, lock: LockMechanics): Boolean {
    if (selected >= 0) {
      z.target = geometry.pinZ[selected]
      val contact = geometry.pinRestY - PICK_GAP
      if (lock.heldPin == selected) y.snap(contact + lock.keyLift(selected)) else y.target = contact
    } else {
      z.target = geometry.pickParkedZ
      y.target = geometry.pickParkedY
    }
    var moving = z.step(dt)
    moving = y.step(dt) or moving
    return moving
  }

  private companion object {
    /** Daylight between the hook tip and the pin it is about to lift. */
    const val PICK_GAP = 0.002f
    const val ARRIVED = 0.006f
  }
}
