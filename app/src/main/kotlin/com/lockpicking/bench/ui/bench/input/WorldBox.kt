package com.lockpicking.bench.ui.bench.input

/** An axis-aligned box in world space, from [min] to [max] on each axis. */
class WorldBox(val min: FloatArray, val max: FloatArray) {
  /** The box with its floor raised to [y], or null if that leaves nothing of it. */
  fun above(y: Float): WorldBox? =
    if (y >= max[1]) null else WorldBox(floatArrayOf(min[0], maxOf(min[1], y), min[2]), max.copyOf())

  /** Its eight corners, each an x, y, z triple. */
  fun corners(): List<FloatArray> = (0 until CORNERS).map { k ->
    floatArrayOf(
      if (k and 1 == 0) min[0] else max[0],
      if (k and 2 == 0) min[1] else max[1],
      if (k and Z_BIT == 0) min[2] else max[2],
    )
  }

  companion object {
    /** The smallest box around [points], each an x, y, z triple. */
    fun around(points: List<FloatArray>): WorldBox = WorldBox(
      FloatArray(AXES) { axis -> points.minOf { it[axis] } },
      FloatArray(AXES) { axis -> points.maxOf { it[axis] } },
    )

    private const val AXES = 3
    private const val CORNERS = 8

    /** Bit of a corner's index that picks [max] over [min] on z (x is bit 1, y bit 2). */
    private const val Z_BIT = 4
  }
}

/**
 * The screen rectangle, in the view's pixels, that covers [points] given in SceneView's
 * normalized view coordinates (0..1 across, 0 at the bottom), grown by [pad] pixels.
 */
fun screenRectAround(points: List<Pair<Float, Float>>, width: Float, height: Float, pad: Float): TouchRect = TouchRect(
  left = points.minOf { it.first } * width - pad,
  top = (1f - points.maxOf { it.second }) * height - pad,
  right = points.maxOf { it.first } * width + pad,
  bottom = (1f - points.minOf { it.second }) * height + pad,
)
