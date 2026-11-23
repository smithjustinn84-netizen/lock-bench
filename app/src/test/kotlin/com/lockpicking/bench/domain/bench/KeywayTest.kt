package com.lockpicking.bench.domain.bench

import com.lockpicking.bench.testing.testGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeywayTest {
  private val keyway = Keyway(testGeometry)

  @Test
  fun `the mouth is on the side the pick is parked on`() {
    // The test lock parks the pick at +Z, so pin 4 is the first one in.
    assertTrue(keyway.mouthZ > testGeometry.pinZ[3])
    assertTrue(keyway.backZ < testGeometry.pinZ[0])
    assertTrue(keyway.depthOf(3) < keyway.depthOf(2))
    assertTrue(keyway.depthOf(1) < keyway.depthOf(0))
  }

  @Test
  fun `a model built the other way round is read the other way round`() {
    val flipped = Keyway(testGeometry.copy(pickParkedZ = -0.20f))
    assertTrue(flipped.depthOf(0) < flipped.depthOf(3))
    assertEquals(flipped.zAt(0f), flipped.mouthZ, 0f)
    assertTrue(flipped.mouthZ < testGeometry.pinZ[0])
  }

  @Test
  fun `depth and position convert both ways and stop at the ends`() {
    for (pin in 0 until testGeometry.pinCount) {
      assertEquals(testGeometry.pinZ[pin], keyway.zAt(keyway.depthOf(pin)), 1e-5f)
    }
    assertEquals(keyway.mouthZ, keyway.zAt(-1f), 0f)
    assertEquals(keyway.backZ, keyway.zAt(2f), 0f)
    assertEquals(0f, keyway.depthAt(testGeometry.pickParkedZ), 0f)
  }

  @Test
  fun `the pins are evenly spread across the keyway`() {
    val depths = (testGeometry.pinCount - 1 downTo 0).map { keyway.depthOf(it) }
    assertEquals(listOf(0.125f, 0.375f, 0.625f, 0.875f), depths.map { Math.round(it * 1000f) / 1000f })
  }

  @Test
  fun `a pin is reached only from under its chamber`() {
    for (pin in 0 until testGeometry.pinCount) {
      val z = testGeometry.pinZ[pin]
      assertEquals(pin, keyway.pinAt(z))
      assertEquals(pin, keyway.pinAt(z + 0.010f))
      assertEquals(pin, keyway.pinAt(z - 0.010f))
    }
    assertEquals("halfway between two chambers is a land", -1, keyway.pinAt(0f))
    assertEquals(-1, keyway.pinAt(testGeometry.pinZ[0] - 0.015f))
    assertEquals("and so is the parked spot", -1, keyway.pinAt(testGeometry.pickParkedZ))
  }

  @Test
  fun `a pick on its way in from outside is not in the keyway yet`() {
    assertFalse(keyway.contains(testGeometry.pickParkedZ))
    assertTrue(keyway.contains(keyway.mouthZ))
    assertTrue(keyway.contains(0f))
    assertTrue(keyway.contains(keyway.backZ))
  }
}
