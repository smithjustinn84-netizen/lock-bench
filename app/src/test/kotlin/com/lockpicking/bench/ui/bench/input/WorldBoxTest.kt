package com.lockpicking.bench.ui.bench.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorldBoxTest {
  @Test
  fun `the box around points spans them and has eight corners`() {
    val box = WorldBox.around(listOf(floatArrayOf(0f, 2f, -1f), floatArrayOf(3f, -2f, 1f)))
    assertEquals(listOf(0f, -2f, -1f), box.min.toList())
    assertEquals(listOf(3f, 2f, 1f), box.max.toList())
    assertEquals(8, box.corners().map { it.toList() }.toSet().size)
  }

  @Test
  fun `raising the floor keeps only what is above it`() {
    val box = WorldBox(floatArrayOf(0f, 0f, 0f), floatArrayOf(1f, 1f, 1f))
    assertEquals(0.3f, box.above(0.3f)!!.min[1], 0f)
    assertEquals(0f, box.above(-1f)!!.min[1], 0f)
    assertNull(box.above(1f))
  }

  @Test
  fun `view coordinates count up from the bottom, screen pixels down from the top`() {
    val rect = screenRectAround(listOf(0.25f to 0.75f, 0.5f to 0.5f), width = 1000f, height = 400f, pad = 10f)
    assertEquals(TouchRect(240f, 90f, 510f, 210f), rect)
    assertTrue(rect.contains(300f, 150f))
    assertFalse(rect.contains(300f, 300f))
  }
}
