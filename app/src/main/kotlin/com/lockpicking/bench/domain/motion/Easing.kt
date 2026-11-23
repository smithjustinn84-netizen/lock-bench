package com.lockpicking.bench.domain.motion

/**
 * A CSS-style cubic Bézier timing curve from (0, 0) to (1, 1) through two control points.
 * The same shape as Compose's `CubicBezierEasing`, without the dependency.
 */
internal class CubicBezier(
  private val x1: Float,
  private val y1: Float,
  private val x2: Float,
  private val y2: Float,
) {
  fun transform(fraction: Float): Float {
    val x = fraction.coerceIn(0f, 1f)
    if (x == 0f || x == 1f) return x
    // Bisect for the curve parameter whose x matches; x(t) is monotonic for 0 <= x1, x2 <= 1.
    var lo = 0f
    var hi = 1f
    var t = x
    repeat(BISECTION_STEPS) {
      val estimate = bezier(t, x1, x2)
      if (estimate < x) lo = t else hi = t
      t = (lo + hi) / 2f
    }
    return bezier(t, y1, y2)
  }

  private fun bezier(t: Float, p1: Float, p2: Float): Float {
    val u = 1f - t
    return 3f * u * u * t * p1 + 3f * u * t * t * p2 + t * t * t
  }

  companion object {
    private const val BISECTION_STEPS = 24

    /** Material's standard curve: quick out, gentle landing. */
    val FastOutSlowIn = CubicBezier(0.4f, 0f, 0.2f, 1f)
  }
}
