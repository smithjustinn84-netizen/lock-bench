package com.lockpicking.bench.ui.bench.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BenchTouchRouterTest {
  /** Everything the router asked of the bench, in order. */
  private val calls = mutableListOf<String>()

  /** Where the bench says the pick is: it follows what the router asks, as the real one does. */
  private var depth = 0f

  private val pick = object : PickInput {
    override val pickDepth: Float get() = depth
    override fun onPickMoved(depth: Float, lift: Float) {
      this@BenchTouchRouterTest.depth = depth.coerceIn(0f, 1f)
      calls += "pick ${"%.2f".format(depth)} ${"%.2f".format(lift)}"
    }
    override fun onPickRested() { calls += "rest" }
    override fun onPickTapped(across: Float) { calls += "tap ${"%.2f".format(across)}" }
  }
  private val wrench = object : WrenchInput {
    override fun onWrenchPulled(amount: Float) { calls += "pull ${"%.2f".format(amount)}" }
    override fun onWrenchReleased() { calls += "wrench off" }
  }

  private val router = BenchTouchRouter(pick, wrench, touchSlop = 10f).apply {
    dragRangePx = 200f
    depthRangePx = 400f
    wrenchThrowPx = 100f
    wrenchBounds = ZONE
    pickBounds = PAD
  }

  private fun down(id: Int, x: Float, y: Float) = router.onDown(id, x, y)

  @Test
  fun `a thumb in the tension zone is a hand on the wrench`() {
    assertTrue(down(0, ZONE_X, 400f))
    assertTrue(router.onMove(0, ZONE_X, 450f))
    assertTrue(router.onUp(0))
    assertEquals(listOf("pull 0.00", "pull ${"%.2f".format(WrenchThrow.torqueAt(0.5f))}", "wrench off"), calls)
    assertFalse(router.ownsGesture)
  }

  @Test
  fun `the wrench pulls down from wherever the thumb landed and keeps going outside the zone`() {
    down(0, ZONE_X, 690f)
    router.onMove(0, ZONE_X, 790f)
    router.onMove(0, ZONE_X, 2000f)
    assertEquals("pull 1.00", calls.last())
    router.onMove(0, ZONE_X, 600f)
    assertEquals("pushing up past the start is no torque, not negative", "pull 0.00", calls.last())
  }

  @Test
  fun `a thumb at rest does not send a stream of pulls`() {
    down(0, ZONE_X, 400f)
    router.onMove(0, ZONE_X, 450f)
    router.onMove(0, ZONE_X, 450.1f)
    router.onMove(0, ZONE_X, 450f)
    assertEquals(2, calls.count { it.startsWith("pull") })
  }

  @Test
  fun `sliding across the pad moves the pick along the keyway without lifting it`() {
    assertTrue(down(0, 700f, 500f))
    assertTrue(calls.isEmpty())
    router.onMove(0, 900f, 500f)
    assertEquals(listOf("pick 0.50 0.00"), calls)
    // A wobble inside the slop while sliding is not a lift.
    router.onMove(0, 1000f, 492f)
    assertEquals("pick 0.75 0.00", calls.last())
  }

  @Test
  fun `sliding up the pad lifts the tip from the lowest point the thumb has been`() {
    down(0, 700f, 500f)
    router.onMove(0, 700f, 390f)
    assertEquals("pick 0.00 0.50", calls.last())
    // Down past where it landed: that is the new floor.
    router.onMove(0, 700f, 560f)
    assertEquals("pick 0.00 0.00", calls.last())
    router.onMove(0, 700f, 450f)
    assertEquals("pick 0.00 0.50", calls.last())
    router.onMove(0, 700f, -2000f)
    assertEquals("the lift tops out just past full travel", "pick 0.00 1.10", calls.last())
  }

  @Test
  fun `the pick stays where it was when the thumb comes off and goes back on`() {
    down(0, 700f, 500f)
    router.onMove(0, 900f, 500f)
    router.onUp(0)
    assertEquals("rest", calls.last())
    // The thumb lands somewhere else on the pad entirely.
    down(0, 1000f, 300f)
    router.onMove(0, 1100f, 300f)
    assertEquals("pick 0.75 0.00", calls.last())
  }

  @Test
  fun `a thumb that overruns the end of the keyway does not have to come all the way back`() {
    down(0, 700f, 500f)
    router.onMove(0, 1500f, 500f)
    assertEquals("the push on the back wall is passed on", "pick 1.06 0.00", calls.last())
    router.onMove(0, 1460f, 500f)
    assertEquals("pick 0.96 0.00", calls.last())
  }

  @Test
  fun `wrench first, then the other thumb works the pick`() {
    down(0, ZONE_X, 400f)
    router.onMove(0, ZONE_X, 450f)
    assertTrue(down(1, 700f, 500f))
    router.onMove(1, 800f, 390f)
    assertEquals("pick 0.25 0.50", calls.last())

    // The pick thumb lifts; the wrench is still held.
    assertTrue(router.onUp(1))
    assertEquals("rest", calls.last())
    assertTrue(router.ownsGesture)
    router.onMove(0, ZONE_X, 460f)
    assertTrue(calls.last().startsWith("pull"))
    router.onUp(0)
    assertEquals("wrench off", calls.last())
    assertFalse(router.ownsGesture)
  }

  @Test
  fun `pick first, then the other thumb takes the wrench`() {
    assertTrue(down(0, 700f, 500f))
    assertTrue(down(1, ZONE_X, 400f))
    router.onMove(1, ZONE_X, 500f)
    router.onMove(0, 700f, 450f)
    assertEquals(listOf("pull 0.00", "pull 1.00", "pick 0.00 0.20"), calls)
    // The wrench lets go first: the pick is still in the thumb's hand.
    router.onUp(1)
    router.onMove(0, 700f, 430f)
    assertEquals(listOf("wrench off", "pick 0.00 0.30"), calls.takeLast(2))
  }

  @Test
  fun `a thumb that stays inside the slop is a tap on that part of the pad`() {
    down(0, ZONE_X, 400f)
    down(1, 700f, 500f)
    router.onMove(1, 705f, 495f)
    router.onUp(1)
    down(1, 1000f, 500f)
    router.onUp(1)
    assertEquals(listOf("pull 0.00", "tap 0.20", "tap 0.80"), calls)
  }

  @Test
  fun `a gesture that starts off the bench is the camera's throughout`() {
    assertFalse(down(0, 400f, 100f))
    assertFalse(router.onMove(0, 420f, 120f))
    // A second finger lands on the pad and a third in the tension zone: neither is picked up.
    assertFalse(down(1, 700f, 500f))
    assertFalse(down(2, ZONE_X, 400f))
    assertFalse(router.onMove(1, 800f, 400f))
    assertFalse(router.onUp(2))
    assertFalse(router.onUp(1))
    assertFalse(router.onUp(0))
    assertTrue(calls.isEmpty())
    // The next gesture starts clean.
    assertTrue(down(0, 700f, 500f))
  }

  @Test
  fun `once the bench owns a gesture a stray finger never reaches the camera`() {
    down(0, ZONE_X, 400f)
    assertTrue(down(1, 400f, 100f))
    assertTrue(router.onMove(1, 420f, 150f))
    assertTrue(router.onUp(1))
    assertEquals(listOf("pull 0.00"), calls)
  }

  @Test
  fun `the drawer keeps its touches even where it lies over the pad`() {
    router.blocked = listOf(TouchRect(600f, 0f, 1100f, 300f))
    down(0, ZONE_X, 400f)
    assertTrue(down(1, 700f, 250f))
    router.onMove(1, 900f, 100f)
    router.onUp(1)
    assertEquals(listOf("pull 0.00"), calls)
  }

  @Test
  fun `only one thumb holds the wrench and only one the pick`() {
    down(0, ZONE_X, 400f)
    down(1, ZONE_X + 10f, 420f)
    down(2, 700f, 500f)
    down(3, 800f, 500f)
    router.onMove(1, ZONE_X + 10f, 600f)
    router.onMove(3, 1000f, 300f)
    assertEquals(listOf("pull 0.00"), calls)
    // The spare fingers coming up change nothing.
    router.onUp(1)
    router.onUp(3)
    assertEquals(listOf("pull 0.00"), calls)
  }

  @Test
  fun `a cancelled gesture lets go of everything`() {
    down(0, ZONE_X, 400f)
    down(1, 700f, 500f)
    router.onMove(1, 700f, 400f)
    calls.clear()
    assertTrue(router.onCancel())
    assertEquals(listOf("wrench off", "rest"), calls)
    assertFalse(router.ownsGesture)
    assertFalse("nothing is left over for the next gesture", down(0, 400f, 100f))
  }

  @Test
  fun `letting go keeps the gesture but drops the wrench and the pick`() {
    down(0, ZONE_X, 400f)
    down(1, 700f, 500f)
    calls.clear()
    router.letGo()
    assertEquals(listOf("wrench off", "rest"), calls)
    // The thumbs are still down: they must not start orbiting the camera, nor do anything else.
    assertTrue(router.onMove(0, ZONE_X, 480f))
    assertTrue(router.onMove(1, 900f, 300f))
    router.onUp(0)
    router.onUp(1)
    assertEquals(2, calls.size)
  }

  @Test
  fun `the throw curve spends most of its travel on light torque`() {
    assertEquals(0f, WrenchThrow.torqueAt(0f), 0f)
    assertEquals(1f, WrenchThrow.torqueAt(1f), 0f)
    assertTrue(WrenchThrow.torqueAt(0.5f) < 0.4f)
    assertEquals(0.5f, WrenchThrow.fractionAt(WrenchThrow.torqueAt(0.5f)), 0.01f)
  }

  private companion object {
    /** A landscape screen: the tension zone on the left, the pick pad on the right. */
    val ZONE = TouchRect(0f, 200f, 200f, 800f)
    val PAD = TouchRect(600f, 200f, 1100f, 800f)
    const val ZONE_X = 100f
  }
}
