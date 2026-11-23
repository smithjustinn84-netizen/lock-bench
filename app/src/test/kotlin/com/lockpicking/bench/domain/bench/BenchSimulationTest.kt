package com.lockpicking.bench.domain.bench

import com.lockpicking.bench.domain.lock.LockEvent
import com.lockpicking.bench.domain.lock.PinState
import com.lockpicking.bench.testing.bindingOrder
import com.lockpicking.bench.testing.testGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BenchSimulationTest {
  private val events = mutableListOf<LockEvent>()
  private val sim = BenchSimulation(testGeometry) { events += it }

  /** Steps at 60 fps until nothing moves. Fails if the bench never settles. */
  private fun settle() {
    repeat(MAX_FRAMES) { if (!sim.advance(FRAME)) return }
    throw AssertionError("bench still moving after $MAX_FRAMES frames")
  }

  @Test
  fun `a fresh bench is already at rest`() {
    assertFalse(sim.advance(FRAME))
  }

  @Test
  fun `tension seats the wrench`() {
    sim.setTension(true)
    settle()
    assertTrue(sim.snapshot().tension)
    assertEquals(1f, sim.pose.wrenchSeat, 0f)
  }

  @Test
  fun `a nudge lifts the binding pin to the shear line and sets it`() {
    sim.setTension(true)
    val binding = sim.snapshot().bindingPin
    sim.nudge(binding)
    settle()
    assertEquals(PinState.Set, sim.readout.state(binding))
    assertEquals(1, sim.snapshot().setCount)
    assertEquals(1, sim.snapshot().setPulse)
    assertTrue(events.single() is LockEvent.PinSet)
  }

  @Test
  fun `a nudge on a pin that is not binding sets nothing`() {
    sim.setTension(true)
    sim.nudge(1)
    settle()
    assertEquals(0, sim.snapshot().setCount)
    assertEquals(0f, sim.readout.keyLift(1), 0f)
  }

  @Test
  fun `picking in binding order opens the lock and swings the shackle`() {
    sim.setTension(true)
    for (pin in bindingOrder) {
      sim.nudge(pin)
      settle()
    }
    val snapshot = sim.snapshot()
    assertTrue(snapshot.opened)
    assertEquals(-1, snapshot.selectedPin)
    assertEquals(LockEvent.Opened, events.last())
    assertEquals(testGeometry.openDegrees, sim.pose.plugDegrees, 0f)
    assertEquals(testGeometry.shackleTravel, sim.pose.shackleLift, 0f)
    assertTrue(sim.pose.shackleSwingDegrees < -70f)
  }

  @Test
  fun `an open lock ignores pin input and tension`() {
    openLock()
    sim.hold(0, 0.01f)
    sim.setTension(false)
    settle()
    assertEquals(-1, sim.snapshot().selectedPin)
    assertTrue(sim.snapshot().opened)
  }

  @Test
  fun `reset swings the shackle home before the plug turns back`() {
    openLock()
    sim.reset()
    // Partway through, the lock is still open: the drivers only fall once the plug is home.
    repeat(10) { sim.advance(FRAME) }
    assertTrue(sim.snapshot().opened)
    settle()
    val snapshot = sim.snapshot()
    assertFalse(snapshot.opened)
    assertFalse(snapshot.tension)
    assertEquals(0, snapshot.setCount)
    assertEquals(0f, sim.pose.plugDegrees, 0f)
    assertEquals(0f, sim.pose.shackleLift, 0f)
    assertEquals(0f, sim.pose.shackleSwingDegrees, 0f)
    assertEquals(0f, sim.pose.wrenchSeat, 0f)
    assertEquals(LockEvent.Relocked, events.last())
  }

  @Test
  fun `the pick waits under the selected pin and parks when the selection clears`() {
    sim.hold(3, 0f)
    settle()
    assertEquals(testGeometry.pinZ[3], sim.pose.pickZ, 0f)
    sim.letGo()
    sim.reset()
    settle()
    assertEquals(testGeometry.pickParkedZ, sim.pose.pickZ, 0f)
    assertEquals(testGeometry.pickParkedY, sim.pose.pickY, 0f)
  }

  @Test
  fun `ghost and explode ease to their targets`() {
    sim.setGhost(true)
    sim.setExplode(1f)
    settle()
    assertTrue(sim.pose.bodyAlpha < 0.2f)
    assertEquals(1f, sim.pose.explode, 0f)
  }

  @Test
  fun `pin motion bumps the motion tick and a settled bench stops bumping it`() {
    sim.hold(0, 0f)
    settle()
    sim.hold(0, 0.02f)
    val before = sim.motionTick
    sim.advance(FRAME)
    assertTrue(sim.motionTick > before)
    sim.letGo()
    settle()
    val settled = sim.motionTick
    sim.advance(FRAME)
    assertEquals(settled, sim.motionTick)
  }

  private fun openLock() {
    sim.setTension(true)
    for (pin in bindingOrder) {
      sim.nudge(pin)
      settle()
    }
    check(sim.snapshot().opened)
  }

  private companion object {
    const val FRAME = 1f / 60f
    const val MAX_FRAMES = 1200
  }
}
