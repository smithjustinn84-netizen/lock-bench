package com.lockpicking.bench.domain.bench

import com.lockpicking.bench.domain.lock.LockEvent
import com.lockpicking.bench.domain.lock.LockMechanics
import com.lockpicking.bench.domain.lock.PinState
import com.lockpicking.bench.domain.model.LockGeometry
import com.lockpicking.bench.testing.american1100Geometry
import com.lockpicking.bench.testing.master3Geometry
import com.lockpicking.bench.testing.trainerGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rake, scrubbed by a scripted thumb: the pick driven back and forth along the whole
 * keyway on a triangle wave while its lift wanders, as a hand's does.
 */
class RakeTest {
  private val events = mutableListOf<LockEvent>()

  private fun bench(geometry: LockGeometry, order: List<Int>? = null) = BenchSimulation(geometry) { events += it }.also {
    if (order != null) it.deal(order)
    it.setTool(PickTool.Rake)
  }

  private fun triangle(t: Float, hz: Float): Float {
    val phase = (t * hz) % 1f
    return if (phase < 0.5f) phase * 2f else 2f - phase * 2f
  }

  private fun BenchSimulation.pull(amount: Float) {
    pullWrench(amount)
    settle()
  }

  private fun BenchSimulation.settle() {
    repeat(MAX_FRAMES) { if (!advance(FRAME)) return }
    throw AssertionError("bench still moving after $MAX_FRAMES frames")
  }

  /** Scrubs for [seconds] at [hz] strokes a second, lift wandering between [low] and [high]. Stops if the lock opens. */
  private fun BenchSimulation.scrub(seconds: Float, hz: Float = 2f, low: Float = 0.030f, high: Float = 0.065f, fps: Int = 60) {
    for (frame in 0 until (seconds * fps).toInt()) {
      val t = frame.toFloat() / fps
      movePick(triangle(t, hz), low + (high - low) * triangle(t, WANDER_HZ))
      advance(1f / fps)
      if (snapshot().opened) return
    }
  }

  /** Parks the lead peak under whichever pin is binding and rocks it up to the shear line and back, [times] over. */
  private fun BenchSimulation.rock(times: Int) {
    repeat(times) {
      val pin = snapshot().bindingPin
      if (snapshot().opened || pin < 0) return
      val top = geometry.pinSpecs[pin].liftToShear + 0.004f
      for (frame in 0 until ROCK_FRAMES) {
        movePick(keyway.depthOf(pin), top * triangle(frame.toFloat() / ROCK_FRAMES, 1f))
        advance(FRAME)
        if (snapshot().opened) return
      }
    }
  }

  private fun permutations(n: Int): List<List<Int>> =
    if (n == 1) listOf(listOf(0)) else permutations(n - 1).flatMap { p -> (0..p.size).map { i -> p.take(i) + (n - 1) + p.drop(i) } }

  // ── Plain pins ──────────────────────────────────────────────────────────────────────────────

  @Test
  fun `the laminated lock rakes open in a few strokes, whichever way it binds`() {
    for (order in permutations(4)) {
      val sim = bench(master3Geometry, order)
      sim.pull(EASED)
      sim.scrub(seconds = 4f)
      assertTrue("order $order", sim.snapshot().opened)
    }
  }

  @Test
  fun `it rakes open the same at half the frame rate and at a faster stroke`() {
    for ((fps, hz) in listOf(30 to 2f, 60 to 3f, 20 to 2f)) {
      val sim = bench(master3Geometry)
      sim.pull(EASED)
      sim.scrub(seconds = 4f, hz = hz, fps = fps)
      assertTrue("$fps fps at $hz strokes a second", sim.snapshot().opened)
    }
  }

  @Test
  fun `a rake held too low sets nothing`() {
    val sim = bench(master3Geometry)
    sim.pull(EASED)
    sim.scrub(seconds = 3f, low = 0.010f, high = 0.020f)
    assertEquals(0, sim.snapshot().setCount)
  }

  @Test
  fun `under a firm wrench the rake oversets a pin and it stays jammed`() {
    val sim = bench(master3Geometry)
    sim.pull(FIRM)
    sim.scrub(seconds = 4f)
    assertFalse(sim.snapshot().opened)
    val jammed = sim.snapshot().oversetPin
    assertTrue(jammed >= 0)
    // Taking the rake out does not free it: only easing the wrench does.
    sim.letGo()
    sim.settle()
    assertEquals(jammed, sim.snapshot().oversetPin)
    val sets = sim.snapshot().setCount
    sim.pull(EASED)
    assertEquals(-1, sim.snapshot().oversetPin)
    assertEquals(sets, sim.snapshot().setCount)
    assertTrue(events.any { it is LockEvent.OversetReleased })
  }

  @Test
  fun `under a heavy wrench the rake moves nothing`() {
    val sim = bench(master3Geometry)
    sim.pull(HEAVY)
    sim.scrub(seconds = 3f)
    assertEquals(0, sim.snapshot().setCount)
    assertEquals(-1, sim.snapshot().oversetPin)
  }

  @Test
  fun `under a light wrench a passing peak does not overset the pin it set`() {
    val sim = bench(master3Geometry)
    sim.pull(EASED)
    // High enough to carry every pin well past its shear line.
    sim.scrub(seconds = 4f, low = 0.070f, high = 0.075f)
    assertTrue(sim.snapshot().opened)
    assertFalse(events.any { it is LockEvent.PinOverset })
  }

  // ── Spools ──────────────────────────────────────────────────────────────────────────────────

  @Test
  fun `scrubbing the trainer ends in a false set, whichever way it binds`() {
    for (order in permutations(4)) {
      events.clear()
      val sim = bench(trainerGeometry, order)
      sim.pull(EASED)
      sim.scrub(seconds = 6f)
      assertFalse("order $order", sim.snapshot().opened)
      assertTrue("order $order", events.any { it is LockEvent.FalseSet })
      // Both plain pins went; at least one spool is still there for the plug to hang on.
      assertEquals(PinState.Set, sim.readout.state(0))
      assertEquals(PinState.Set, sim.readout.state(3))
      assertTrue("order $order", sim.snapshot().falseSetPin >= 0)
    }
  }

  @Test
  fun `a faster or a slower scrub does not get through the spools either`() {
    for (hz in listOf(0.5f, 1.3f, 3f)) {
      val sim = bench(trainerGeometry)
      sim.pull(EASED)
      sim.scrub(seconds = 8f, hz = hz)
      assertFalse("$hz strokes a second", sim.snapshot().opened)
    }
  }

  @Test
  fun `rocking a peak under each spool finishes what scrubbing could not`() {
    val sim = bench(trainerGeometry)
    sim.pull(EASED)
    sim.scrub(seconds = 4f)
    assertFalse(sim.snapshot().opened)
    sim.rock(times = 6)
    assertTrue(sim.snapshot().opened)
    assertTrue(events.any { it is LockEvent.CounterRotation && it.step == LockMechanics.COUNTER_ROTATION_STEPS })
  }

  // ── Serrated drivers ────────────────────────────────────────────────────────────────────────

  @Test
  fun `scrubbing the aluminium lock clicks its serrations and sets nothing`() {
    for (order in permutations(5).filterIndexed { i, _ -> i % 6 == 0 }) {
      events.clear()
      val sim = bench(american1100Geometry, order)
      sim.pull(EASED)
      sim.scrub(seconds = 5f, high = 0.070f)
      assertFalse("order $order", sim.snapshot().opened)
      assertTrue("order $order", american1100Geometry.pinSpecs.indices.none { sim.readout.state(it) == PinState.Set && american1100Geometry.pinSpecs[it].serrated })
    }
    assertTrue(events.any { it is LockEvent.SerrationClick })
  }

  @Test
  fun `a firm wrench makes a serration catch the rake`() {
    val sim = bench(american1100Geometry)
    sim.pull(FIRM)
    sim.scrub(seconds = 3f, high = 0.070f)
    assertEquals(0, sim.snapshot().setCount)
    assertTrue(events.any { it is LockEvent.SerrationHeld })
  }

  @Test
  fun `rocking a peak under a serrated pin carries it through its grooves to the set`() {
    val sim = bench(american1100Geometry)
    sim.pull(EASED)
    val pin = sim.snapshot().bindingPin
    assertTrue(american1100Geometry.pinSpecs[pin].serrated)
    sim.rock(times = 1)
    assertEquals(PinState.Set, sim.readout.state(pin))
    assertEquals(listOf(1, 2, 3), events.filterIsInstance<LockEvent.SerrationClick>().filter { it.pin == pin }.map { it.notch })
  }

  @Test
  fun `rocking pin by pin opens the aluminium lock`() {
    val sim = bench(american1100Geometry)
    sim.pull(EASED)
    sim.rock(times = 20)
    assertTrue(sim.snapshot().opened)
  }

  // ── The tool in hand ────────────────────────────────────────────────────────────────────────

  @Test
  fun `swapping the rake for the hook keeps the wrench and every set`() {
    val sim = bench(trainerGeometry)
    sim.pull(EASED)
    sim.scrub(seconds = 4f)
    val sets = sim.snapshot().setCount
    assertTrue(sets >= 2)
    sim.setTool(PickTool.Hook)
    sim.settle()
    assertEquals(PickTool.Hook, sim.pose.tool)
    assertEquals(sets, sim.snapshot().setCount)
    assertEquals(EASED, sim.pose.torque, 0.01f)
    assertEquals(-1, sim.snapshot().selectedPin)
    // And the hook finishes the job.
    repeat(8) {
      if (sim.snapshot().opened) return@repeat
      sim.nudge(sim.snapshot().bindingPin)
      sim.settle()
    }
    assertTrue(sim.snapshot().opened)
  }

  @Test
  fun `a tap does nothing with the rake in`() {
    val sim = bench(master3Geometry)
    sim.pull(EASED)
    sim.nudge(sim.snapshot().bindingPin)
    sim.settle()
    assertEquals(0, sim.snapshot().setCount)
  }

  @Test
  fun `a rake held still lets the bench go to sleep, and letting go drops what it held`() {
    val sim = bench(trainerGeometry)
    sim.pull(EASED)
    sim.scrub(seconds = 2f)
    sim.movePick(0.5f, 0.05f)
    sim.settle()
    assertFalse(sim.advance(FRAME))
    sim.letGo()
    sim.settle()
    assertTrue((0 until 4).all { sim.readout.state(it) == PinState.Set || sim.readout.keyLift(it) == 0f })
  }

  @Test
  fun `the rake is not stopped by the lands and says nothing of them`() {
    val sim = bench(master3Geometry)
    sim.scrub(seconds = 2f)
    assertFalse(events.any { it == LockEvent.WardHit || it is LockEvent.PinTouched || it is LockEvent.HardStop })
  }

  @Test
  fun `a stop is heard once for each pin, not once a stroke`() {
    val sim = bench(american1100Geometry)
    sim.pull(FIRM)
    sim.scrub(seconds = 4f, hz = 3f, high = 0.070f)
    // Twelve strokes; without quietening every one of them would knock on the caught pin.
    assertTrue(events.count { it is LockEvent.SerrationHeld } <= 12)
    assertTrue(events.count { it is LockEvent.SerrationClick } <= 12 * LockMechanics.SERRATIONS)
  }

  @Test
  fun `the blade never rides above the tip of a pin it is under`() {
    val sim = bench(trainerGeometry)
    val rake = trainerGeometry.rake!!
    sim.pull(EASED)
    for (frame in 0 until 360) {
      val t = frame / 60f
      sim.movePick(triangle(t, 2f), 0.030f + 0.035f * triangle(t, WANDER_HZ))
      sim.advance(FRAME)
      val z = sim.pose.pickZ
      if (!sim.keyway.contains(z)) continue
      val lift = sim.pose.pickY - (trainerGeometry.pinRestY - 0.002f)
      val reach = trainerGeometry.pickFulcrumZ - z
      for (pin in 0 until 4) {
        val behind = trainerGeometry.pinZ[pin] - z
        val o = rake.heightAt(behind)
        if (o.isNaN()) continue
        val blade = lift * (1f - behind / reach) + o
        assertTrue("frame $frame pin $pin: blade $blade above pin ${sim.readout.keyLift(pin)}", blade <= sim.readout.keyLift(pin) + 0.006f)
      }
    }
  }

  private companion object {
    const val FRAME = 1f / 60f
    const val MAX_FRAMES = 1200
    const val ROCK_FRAMES = 90
    const val WANDER_HZ = 0.7f
    const val EASED = 0.15f
    const val FIRM = 0.5f
    const val HEAVY = 0.8f
  }
}
