package com.lockpicking.bench.domain.bench

import com.lockpicking.bench.domain.model.LockGeometry
import kotlin.math.abs

/**
 * The slot the pick works in, laid out along the plug's axis.
 *
 * A pin can only be reached from directly below its chamber. Between chambers the tip is under
 * a land: the solid metal of the plug, which stands in here for the keyway's warding. The pick
 * can rise only [LAND_CLEARANCE] there before it knocks on it.
 *
 * Depth runs 0 at the mouth of the keyway to 1 at its back wall, whichever way the model's Z
 * happens to run.
 */
class Keyway(geometry: LockGeometry) {
  private val pinZ = geometry.pinZ

  /** Spacing between neighbouring chambers. */
  private val pitch: Float = pinZ.sorted().zipWithNext { a, b -> b - a }.minOrNull() ?: DEFAULT_PITCH

  private val halfChamber = pitch * CHAMBER_SHARE / 2f

  /** World Z of the mouth and of the back wall. */
  val mouthZ: Float
  val backZ: Float

  init {
    // The pick is parked outside the mouth, so the pin nearest to it is the first one in.
    val first = pinZ.minBy { abs(it - geometry.pickParkedZ) }
    val last = pinZ.maxBy { abs(it - geometry.pickParkedZ) }
    val inward = if (last >= first) 1f else -1f
    mouthZ = first - inward * pitch * MOUTH_LEAD
    backZ = last + inward * pitch * BACK_LEAD
  }

  fun zAt(depth: Float): Float = mouthZ + (backZ - mouthZ) * depth.coerceIn(0f, 1f)

  fun depthAt(z: Float): Float = ((z - mouthZ) / (backZ - mouthZ)).coerceIn(0f, 1f)

  /** True while a tip at [z] is inside the keyway and not still on its way in from outside. */
  fun contains(z: Float): Boolean = (z - mouthZ) * (z - backZ) <= 0f

  /** The depth that puts the tip squarely under [pin]. */
  fun depthOf(pin: Int): Float = depthAt(pinZ[pin])

  /** The pin whose chamber a tip at [z] is under, or -1 when it is under a land. */
  fun pinAt(z: Float): Int {
    for (i in pinZ.indices) if (abs(z - pinZ[i]) <= halfChamber) return i
    return -1
  }

  companion object {
    /** How far the tip can rise under a land before it is against the metal. */
    const val LAND_CLEARANCE = 0.004f

    /** The share of the pitch that is chamber; the rest is land. */
    private const val CHAMBER_SHARE = 0.56f

    /** Keyway in front of the first pin and behind the last, in pitches. */
    private const val MOUTH_LEAD = 0.5f
    private const val BACK_LEAD = 0.5f
    private const val DEFAULT_PITCH = 0.04f
  }
}
