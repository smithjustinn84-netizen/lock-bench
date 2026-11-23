package com.lockpicking.bench.domain.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpringValueTest {
  private val spring = SpringValue(0f, stiffness = 120f, epsilon = 0.001f)

  @Test
  fun `a spring at its target does not move`() {
    assertFalse(spring.step(1f / 60f))
    assertEquals(0f, spring.value, 0f)
  }

  @Test
  fun `a spring converges on its target and then reports it has stopped`() {
    spring.target = 1f
    var frames = 0
    while (spring.step(1f / 60f)) check(++frames < 600) { "spring never settled" }
    assertEquals(1f, spring.value, 0f)
    assertFalse(spring.step(1f / 60f))
  }

  @Test
  fun `a critically damped spring does not overshoot`() {
    spring.target = 1f
    repeat(300) {
      spring.step(1f / 60f)
      assertTrue(spring.value <= 1f)
    }
  }

  @Test
  fun `snap jumps straight to a value and stops`() {
    spring.target = 1f
    spring.step(1f / 60f)
    spring.snap(0.5f)
    assertEquals(0.5f, spring.value, 0f)
    assertEquals(0.5f, spring.target, 0f)
    assertFalse(spring.step(1f / 60f))
  }
}

class CubicBezierTest {
  private val curve = CubicBezier.FastOutSlowIn

  @Test
  fun `the curve starts at 0 and ends at 1`() {
    assertEquals(0f, curve.transform(0f), 0f)
    assertEquals(1f, curve.transform(1f), 0f)
  }

  @Test
  fun `the curve only rises`() {
    var previous = 0f
    for (i in 1..100) {
      val y = curve.transform(i / 100f)
      assertTrue(y >= previous)
      previous = y
    }
  }

  @Test
  fun `fast out slow in is ahead of linear at the midpoint`() {
    assertTrue(curve.transform(0.5f) > 0.7f)
  }
}
