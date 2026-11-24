package com.lockpicking.bench.domain.bench

import com.lockpicking.bench.domain.lock.LockEvent
import com.lockpicking.bench.domain.lock.LockMechanics
import com.lockpicking.bench.domain.lock.PinState
import com.lockpicking.bench.domain.model.LockGeometry
import com.lockpicking.bench.testing.american1100Geometry
import com.lockpicking.bench.testing.master3Geometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The locks on the bench besides the trainer, each with the pins its model authors. */
class LockLineupTest {
  private val events = mutableListOf<LockEvent>()

  private fun bench(geometry: LockGeometry) = BenchSimulation(geometry) { events += it }

  private fun BenchSimulation.settle() {
    repeat(MAX_FRAMES) { if (!advance(FRAME)) return }
    throw AssertionError("bench still moving after $MAX_FRAMES frames")
  }

  private fun BenchSimulation.pull(amount: Float) {
    pullWrench(amount)
    settle()
  }

  /** Taps whichever pin is binding until the lock opens or [taps] run out. */
  private fun BenchSimulation.tapBindingPins(taps: Int) {
    repeat(taps) {
      if (snapshot().opened) return
      nudge(snapshot().bindingPin)
      settle()
    }
  }

  @Test
  fun `the laminated lock opens one tap a pin at any working tension`() {
    for (torque in listOf(0.15f, 0.4f, 0.7f)) {
      events.clear()
      val sim = bench(master3Geometry)
      sim.pull(torque)
      sim.tapBindingPins(master3Geometry.pinCount)
      assertTrue("opens at $torque", sim.snapshot().opened)
      // Nothing in it to trip on.
      assertFalse(events.any { it is LockEvent.FalseSet || it is LockEvent.SerrationClick || it is LockEvent.SpoolBlocked })
    }
  }

  @Test
  fun `the aluminium lock has five stacks and its keyway reaches them all`() {
    val sim = bench(american1100Geometry)
    assertEquals(5, sim.readout.pinCount)
    val depths = (0 until 5).map { sim.keyway.depthOf(it) }
    assertEquals(depths.sorted(), depths)
    assertTrue(depths.first() > 0f && depths.last() < 1f)
    for (pin in 0 until 5) assertEquals(pin, sim.keyway.pinAt(american1100Geometry.pinZ[pin]))
  }

  @Test
  fun `the aluminium lock opens under light tension, through its serrations and both false sets`() {
    val sim = bench(american1100Geometry)
    sim.pull(EASED)
    sim.tapBindingPins(3 * american1100Geometry.pinCount)
    assertTrue(sim.snapshot().opened)
    // Three serrated drivers, three grooves each.
    assertEquals(9, events.count { it is LockEvent.SerrationClick })
    assertTrue(events.any { it is LockEvent.FalseSet })
    assertTrue(events.any { it is LockEvent.CounterRotation })
  }

  @Test
  fun `the aluminium lock gives up nothing to a heavy hand`() {
    val sim = bench(american1100Geometry)
    sim.pull(FIRM)
    val binding = sim.snapshot().bindingPin
    assertTrue(american1100Geometry.pinSpecs[binding].serrated)
    // The hand pushes for the shear line and holds there: the first groove catches instead.
    sim.hold(binding, american1100Geometry.pinSpecs[binding].liftToShear)
    sim.settle()
    assertEquals(PinState.Resting, sim.readout.state(binding))
    assertEquals(binding, sim.snapshot().blockedPin)
    assertTrue(events.any { it is LockEvent.SerrationHeld })
    assertEquals(0, sim.snapshot().setCount)

    // Easing the wrench with the pick still on it lets the pin through.
    sim.pull(LockMechanics.T_SERRATION - 0.1f)
    assertEquals(PinState.Set, sim.readout.state(binding))
  }

  @Test
  fun `the default order of a five pin lock names every pin once`() {
    assertEquals(listOf(2, 0, 3, 1, 4), LockMechanics.defaultBindingOrder(5))
    assertEquals(listOf(2, 0, 3, 1), LockMechanics.defaultBindingOrder(4))
    assertEquals(listOf(2, 0, 1), LockMechanics.defaultBindingOrder(3))
  }

  private companion object {
    const val FRAME = 1f / 60f
    const val MAX_FRAMES = 1200
    const val EASED = 0.15f
    const val FIRM = 0.6f
  }
}
