package com.lockpicking.bench.domain.bench

import kotlin.math.PI
import kotlin.math.cos

/**
 * The top edge of a rake's blade: a row of equal humps and the shank behind them, in model
 * units. Authored by the model script and read from the .glb, like every other dimension.
 *
 * Distances run back from the lead peak toward the handle. Heights are relative to the lead
 * peak's top, so they are zero on a peak and negative everywhere else.
 *
 * @property pitch peak to peak. Not the lock's pin pitch: that is what makes a rake a rake.
 * @property depth how far the valleys lie below the peaks.
 * @property shankDrop how far the shank's top edge lies below the peaks.
 */
data class RakeProfile(
  val pitch: Float,
  val depth: Float,
  val peaks: Int,
  val shankDrop: Float,
) {
  /** The nose of the blade: half a wave in front of the lead peak, down in a valley. */
  val front: Float get() = -pitch / 2f

  /** Where the last hump runs out into the shank. */
  private val humpsEnd: Float get() = (peaks - 1) * pitch + pitch / 2f

  /** Height of the blade's edge [behind] the lead peak, or NaN in front of its nose. */
  fun heightAt(behind: Float): Float = when {
    behind < front -> Float.NaN
    behind <= humpsEnd -> -depth * (1f - cos(2.0 * PI * behind / pitch).toFloat()) / 2f
    else -> -shankDrop
  }

  companion object {
    /** The rake the model script builds, for a model that does not say. */
    val Standard = RakeProfile(pitch = 0.036f, depth = 0.014f, peaks = 3, shankDrop = 0.018f)
  }
}
