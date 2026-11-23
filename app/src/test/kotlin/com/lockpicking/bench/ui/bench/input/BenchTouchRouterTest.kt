package com.lockpicking.bench.ui.bench.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BenchTouchRouterTest {
  /** Everything the router asked of the bench, in order. */
  private val calls = mutableListOf<String>()
  private var acceptPins = true

  private val pins = object : PinInput {
    override val acceptsPins: Boolean get() = acceptPins
    override fun onPinPressed(pin: Int) { calls += "press $pin" }
    override fun onPinDragged(pin: Int, fraction: Float) { calls += "drag $pin ${"%.2f".format(fraction)}" }
    override fun onPinReleased() { calls += "release" }
    override fun onPinTapped(pin: Int) { calls += "tap $pin" }
  }
  private val wrench = object : WrenchInput {
    override fun onWrenchPulled(amount: Float) { calls += "pull ${"%.2f".format(amount)}" }
    override fun onWrenchReleased() { calls += "wrench off" }
  }

  private val router = BenchTouchRouter(pins, wrench, touchSlop = 10f).apply {
    dragRangePx = 200f
    wrenchThrowPx = 100f
    wrenchBounds = LEVER
    blocked = listOf(READOUT)
  }

  /** Pin stacks are 100 px columns across the middle of the view, starting at x = 300. */
  private val pinAt = { x: Float, y: Float -> if (y in 200f..600f && x in 300f..699f) ((x - 300f) / 100f).toInt() else -1 }

  private fun down(id: Int, x: Float, y: Float) = router.onDown(id, x, y, pinAt)

  @Test
  fun `a finger on the lever is a hand on the wrench`() {
    assertTrue(down(0, LEVER_X, 700f))
    assertTrue(router.onMove(0, 750f))
    assertTrue(router.onUp(0))
    assertEquals(listOf("pull 0.00", "pull ${"%.2f".format(WrenchThrow.torqueAt(0.5f))}", "wrench off"), calls)
    assertFalse(router.ownsGesture)
  }

  @Test
  fun `the wrench pulls down from wherever the thumb landed and keeps going outside the lever`() {
    down(0, LEVER_X, 790f)
    router.onMove(0, 890f)
    router.onMove(0, 2000f)
    assertEquals("pull 1.00", calls.last())
    router.onMove(0, 700f)
    assertEquals("pushing up past the start is no torque, not negative", "pull 0.00", calls.last())
  }

  @Test
  fun `a thumb at rest does not send a stream of pulls`() {
    down(0, LEVER_X, 700f)
    router.onMove(0, 750f)
    router.onMove(0, 750.1f)
    router.onMove(0, 750f)
    assertEquals(2, calls.count { it.startsWith("pull") })
  }

  @Test
  fun `wrench first, then a second finger drags a pin`() {
    down(0, LEVER_X, 700f)
    router.onMove(0, 750f)
    assertTrue(down(1, 450f, 500f))
    router.onMove(1, 400f)
    assertEquals(listOf("press 1", "drag 1 0.50"), calls.takeLast(2))

    // The pin finger lifts; the wrench is still held.
    assertTrue(router.onUp(1))
    assertEquals("release", calls.last())
    assertTrue(router.ownsGesture)
    router.onMove(0, 760f)
    assertTrue(calls.last().startsWith("pull"))
    router.onUp(0)
    assertEquals("wrench off", calls.last())
    assertFalse(router.ownsGesture)
  }

  @Test
  fun `pin first, then a second finger takes the wrench`() {
    assertTrue(down(0, 350f, 500f))
    assertTrue(down(1, LEVER_X, 700f))
    router.onMove(1, 800f)
    router.onMove(0, 480f)
    assertEquals(listOf("press 0", "pull 0.00", "pull 1.00", "drag 0 0.10"), calls)
    // The wrench lets go first: the pin is still under the pick.
    router.onUp(1)
    router.onMove(0, 460f)
    assertEquals(listOf("wrench off", "drag 0 0.20"), calls.takeLast(2))
  }

  @Test
  fun `a second finger can tap pin after pin while the wrench is held`() {
    down(0, LEVER_X, 700f)
    down(1, 350f, 500f)
    router.onUp(1)
    down(1, 650f, 500f)
    router.onUp(1)
    assertEquals(listOf("pull 0.00", "press 0", "tap 0", "press 3", "tap 3"), calls)
  }

  @Test
  fun `a finger that stays inside the slop is a tap`() {
    down(0, 350f, 500f)
    router.onMove(0, 495f)
    router.onUp(0)
    assertEquals(listOf("press 0", "tap 0"), calls)
  }

  @Test
  fun `a gesture that starts off the bench is the camera's throughout`() {
    assertFalse(down(0, 100f, 100f))
    assertFalse(router.onMove(0, 120f))
    // A second finger lands on a pin and a third on the lever: neither is picked up.
    assertFalse(down(1, 350f, 500f))
    assertFalse(down(2, LEVER_X, 700f))
    assertFalse(router.onMove(1, 400f))
    assertFalse(router.onUp(2))
    assertFalse(router.onUp(1))
    assertFalse(router.onUp(0))
    assertTrue(calls.isEmpty())
    // The next gesture starts clean.
    assertTrue(down(0, 350f, 500f))
  }

  @Test
  fun `once the bench owns a gesture a stray finger never reaches the camera`() {
    down(0, LEVER_X, 700f)
    assertTrue(down(1, 100f, 100f))
    assertTrue(router.onMove(1, 150f))
    assertTrue(router.onUp(1))
    assertEquals(listOf("pull 0.00"), calls)
  }

  @Test
  fun `a finger on a panel does not pick the pin behind it`() {
    down(0, LEVER_X, 700f)
    // The readout panel overlaps pin 0's column.
    assertTrue(down(1, 320f, 580f))
    router.onUp(1)
    assertEquals(listOf("pull 0.00"), calls)
  }

  @Test
  fun `the lever wins over a pin behind it`() {
    router.wrenchBounds = TouchRect(300f, 200f, 400f, 600f)
    down(0, 350f, 400f)
    assertEquals(listOf("pull 0.00"), calls)
  }

  @Test
  fun `an open lock gives its pins to the camera but keeps the lever`() {
    acceptPins = false
    assertFalse(down(0, 350f, 500f))
    router.onUp(0)
    assertTrue(down(0, LEVER_X, 700f))
  }

  @Test
  fun `only one finger holds the wrench and only one the pick`() {
    down(0, LEVER_X, 700f)
    down(1, LEVER_X + 10f, 720f)
    down(2, 350f, 500f)
    down(3, 450f, 500f)
    assertEquals(listOf("pull 0.00", "press 0"), calls)
    // The spare fingers coming up change nothing.
    router.onUp(1)
    router.onUp(3)
    assertEquals(listOf("pull 0.00", "press 0"), calls)
  }

  @Test
  fun `a cancelled gesture lets go of everything`() {
    down(0, LEVER_X, 700f)
    down(1, 350f, 500f)
    router.onMove(1, 400f)
    calls.clear()
    assertTrue(router.onCancel())
    assertEquals(listOf("wrench off", "release"), calls)
    assertFalse(router.ownsGesture)
    assertFalse("nothing is left over for the next gesture", down(0, 100f, 100f))
  }

  @Test
  fun `letting go keeps the gesture but drops the wrench and the pick`() {
    down(0, LEVER_X, 700f)
    down(1, 350f, 500f)
    calls.clear()
    router.letGo()
    assertEquals(listOf("wrench off", "release"), calls)
    // The fingers are still down: they must not start orbiting the camera, nor do anything else.
    assertTrue(router.onMove(0, 780f))
    assertTrue(router.onMove(1, 300f))
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
    val LEVER = TouchRect(800f, 600f, 900f, 800f)
    val READOUT = TouchRect(0f, 550f, 340f, 800f)
    const val LEVER_X = 850f
  }
}
