package com.lockpicking.bench.domain.bench

import com.lockpicking.bench.domain.lock.LockEvent
import com.lockpicking.bench.domain.lock.LockMechanics
import com.lockpicking.bench.domain.lock.PinFeel
import com.lockpicking.bench.domain.lock.PinState
import com.lockpicking.bench.domain.lock.TorqueBand
import com.lockpicking.bench.testing.testGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class BenchSimulationTest {
  private val events = mutableListOf<LockEvent>()
  private val sim = BenchSimulation(testGeometry) { events += it }

  /** The events that mark something happening in the lock, without what the hands only feel on the way. */
  private val milestones get() = events.filterNot {
    it is LockEvent.Grit || it is LockEvent.SpringTick || it is LockEvent.TensionBite || it is LockEvent.PinTouched ||
      it == LockEvent.WardScrape
  }

  /** Slides the pick from one depth to another over [frames], with the tip held at [lift]. */
  private fun slide(from: Float, to: Float, lift: Float = 0f, frames: Int = 60) {
    for (frame in 1..frames) {
      sim.movePick(from + (to - from) * frame / frames, lift)
      sim.advance(FRAME)
    }
    settle()
  }

  /** Steps at 60 fps until nothing moves. Fails if the bench never settles. */
  private fun settle() {
    repeat(MAX_FRAMES) { if (!sim.advance(FRAME)) return }
    throw AssertionError("bench still moving after $MAX_FRAMES frames")
  }

  private fun pull(amount: Float) {
    sim.pullWrench(amount)
    settle()
  }

  /** Taps whichever pin is binding until the lock opens: light enough for the spools to pass. */
  private fun openLock() {
    pull(EASED)
    repeat(2 * testGeometry.pinCount) {
      if (sim.snapshot().opened) return@repeat
      sim.nudge(sim.snapshot().bindingPin)
      settle()
    }
    check(sim.snapshot().opened)
  }

  /** Sets the two standard pins and lifts pin 2 off its flange: the plug drops into the spools. */
  private fun dropIntoFalseSet() {
    repeat(2) {
      sim.nudge(sim.snapshot().bindingPin)
      settle()
    }
    check(sim.snapshot().setCount == 2)
    sim.hold(1, 0.010f)
    settle()
    sim.letGo()
    settle()
    check(sim.snapshot().falseSetPin == 2)
  }

  @Test
  fun `a fresh bench is already at rest`() {
    assertFalse(sim.advance(FRAME))
  }

  @Test
  fun `a hand on the wrench seats it and a pull puts torque on the plug`() {
    pull(0f)
    assertEquals(1f, sim.pose.wrenchSeat, 0f)
    assertEquals(TorqueBand.Slack, sim.snapshot().band)

    pull(0.5f)
    assertEquals(TorqueBand.Firm, sim.snapshot().band)
    assertEquals(0.5f, sim.pose.torque, 0f)
    assertTrue("the plug takes up and the wrench springs round the same way", sim.pose.plugDegrees < 0f && sim.pose.wrenchFlexDegrees < 0f)
  }

  @Test
  fun `torque moving bumps the motion tick and a steady wrench lets the bench sleep`() {
    sim.pullWrench(0.5f)
    val before = sim.motionTick
    sim.advance(FRAME)
    assertTrue(sim.motionTick > before)
    settle()
    val settled = sim.motionTick
    assertFalse(sim.advance(FRAME))
    assertEquals(settled, sim.motionTick)
  }

  @Test
  fun `letting go of the wrench drops every set`() {
    pull(0.5f)
    sim.nudge(sim.snapshot().bindingPin)
    settle()
    assertEquals(1, sim.snapshot().setCount)
    sim.releaseWrench()
    settle()
    assertEquals(0, sim.snapshot().setCount)
    assertEquals(TorqueBand.Slack, sim.snapshot().band)
    assertEquals(0f, sim.pose.wrenchSeat, 0f)
    assertEquals(0f, sim.pose.plugDegrees, 0f)
    assertEquals(LockEvent.Dropped, events.last())
  }

  @Test
  fun `a nudge lifts the binding pin to the shear line and sets it`() {
    pull(0.5f)
    val binding = sim.snapshot().bindingPin
    sim.nudge(binding)
    settle()
    assertEquals(PinState.Set, sim.readout.state(binding))
    assertEquals(1, sim.snapshot().setCount)
    assertEquals(1, sim.snapshot().setPulse)
    assertTrue(milestones.single() is LockEvent.PinSet)
  }

  @Test
  fun `a nudge on a pin that is not binding sets nothing`() {
    pull(0.5f)
    sim.nudge(3)
    settle()
    assertEquals(0, sim.snapshot().setCount)
    assertEquals(0f, sim.readout.keyLift(3), 0f)
  }

  @Test
  fun `a binding pin drags behind the hand and still sets`() {
    pull(0.6f)
    val shear = testGeometry.pinSpecs[0].liftToShear
    sim.hold(0, 0f)
    settle()
    sim.hold(0, shear)
    sim.advance(FRAME)
    val afterOneFrame = sim.readout.keyLift(0)
    assertTrue("it has started but not arrived", afterOneFrame > 0f && afterOneFrame < shear)
    settle()
    assertEquals(PinState.Set, sim.readout.state(0))
  }

  @Test
  fun `a hand ahead of a dragging pin does not overset it`() {
    pull(0.6f)
    sim.hold(0, 0f)
    settle()
    // The finger goes straight to the top of the travel; the pin gets there when it gets there.
    sim.hold(0, sim.maxLift(0))
    settle()
    assertEquals(PinState.Set, sim.readout.state(0))
    assertEquals(-1, sim.snapshot().oversetPin)
  }

  @Test
  fun `pushing on after the set oversets`() {
    pull(0.6f)
    val shear = testGeometry.pinSpecs[0].liftToShear
    sim.hold(0, 0f)
    settle()
    sim.hold(0, shear)
    settle()
    assertEquals(PinState.Set, sim.readout.state(0))
    sim.hold(0, shear + 0.016f)
    settle()
    assertEquals(0, sim.snapshot().oversetPin)
  }

  @Test
  fun `a false set swings the plug well past the sets`() {
    pull(0.5f)
    repeat(2) {
      sim.nudge(sim.snapshot().bindingPin)
      settle()
    }
    val twoSets = abs(sim.pose.plugDegrees)
    sim.hold(1, 0.010f)
    settle()
    assertEquals(2, sim.snapshot().falseSetPin)
    assertEquals(LockMechanics.FALSE_SET_DEGREES, abs(sim.pose.plugDegrees) - twoSets, 0.05f)
    assertTrue(events.any { it is LockEvent.FalseSet })
  }

  @Test
  fun `a tap on a spool under firm torque is blocked and sets nothing`() {
    pull(0.5f)
    dropIntoFalseSet()
    sim.nudge(2)
    settle()
    assertEquals(PinState.Resting, sim.readout.state(2))
    assertTrue(events.contains(LockEvent.SpoolBlocked(2)))
    assertEquals("the pick has let go", -1, sim.snapshot().blockedPin)
  }

  @Test
  fun `easing the wrench while the pick holds a spool turns the plug back and sets it`() {
    pull(0.5f)
    dropIntoFalseSet()
    val falseSet = abs(sim.pose.plugDegrees)
    sim.hold(2, 0f)
    settle()
    sim.hold(2, testGeometry.pinSpecs[2].liftToShear)
    settle()
    assertEquals("held against the flange, the bench is still", 2, sim.snapshot().blockedPin)
    assertEquals(PinState.Resting, sim.readout.state(2))

    sim.pullWrench(EASED)
    // A few frames in, the plug is on its way back but the pin has not set.
    repeat(12) { sim.advance(FRAME) }
    assertTrue(abs(sim.pose.plugDegrees) < falseSet - 1f)
    assertEquals(PinState.Resting, sim.readout.state(2))
    settle()
    assertEquals(PinState.Set, sim.readout.state(2))
    assertEquals(-1, sim.snapshot().blockedPin)
    assertEquals(listOf(1, 2, 3, 4), events.filterIsInstance<LockEvent.CounterRotation>().map { it.step })
  }

  @Test
  fun `picking the binding pin each time opens the lock and swings the shackle`() {
    openLock()
    val snapshot = sim.snapshot()
    assertEquals(-1, snapshot.selectedPin)
    assertEquals(LockEvent.Opened, events.last())
    assertEquals(3, events.count { it is LockEvent.PinSet })
    assertEquals(testGeometry.openDegrees, sim.pose.plugDegrees, 0f)
    assertEquals(testGeometry.shackleTravel, sim.pose.shackleLift, 0f)
    assertTrue(sim.pose.shackleSwingDegrees < -70f)
  }

  @Test
  fun `an open lock ignores the pick and the wrench`() {
    openLock()
    sim.hold(0, 0.01f)
    sim.releaseWrench()
    sim.pullWrench(0.5f)
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
    assertEquals(TorqueBand.Slack, snapshot.band)
    assertEquals(0, snapshot.setCount)
    assertEquals(0f, sim.pose.plugDegrees, 0f)
    assertEquals(0f, sim.pose.shackleLift, 0f)
    assertEquals(0f, sim.pose.shackleSwingDegrees, 0f)
    assertEquals(0f, sim.pose.wrenchSeat, 0f)
    assertEquals(LockEvent.Relocked, events.last())
  }

  @Test
  fun `reset takes the torque off a lock that is being picked`() {
    pull(0.5f)
    sim.nudge(sim.snapshot().bindingPin)
    settle()
    sim.reset()
    settle()
    assertEquals(TorqueBand.Slack, sim.snapshot().band)
    assertEquals(0, sim.snapshot().setCount)
    assertEquals(0f, sim.pose.torque, 0f)
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

  // ── The keyway ──────────────────────────────────────────────────────────────────────────────

  @Test
  fun `sliding the pick down the keyway touches each pin in turn`() {
    slide(0f, 1f)
    // The test lock's keyway runs from pin 4 at the mouth back to pin 1.
    assertEquals(listOf(3, 2, 1, 0), events.filterIsInstance<LockEvent.PinTouched>().map { it.pin })
    assertTrue("with no tension nothing binds", events.filterIsInstance<LockEvent.PinTouched>().all { it.feel == PinFeel.Springy })
    assertTrue("a tip that is not lifted knocks on nothing", events.none { it == LockEvent.WardHit })
  }

  @Test
  fun `the pin that is binding pushes back when the tip finds it`() {
    pull(0.5f)
    val binding = sim.snapshot().bindingPin
    slide(0f, 1f)
    val touches = events.filterIsInstance<LockEvent.PinTouched>()
    assertEquals(PinFeel.Binding, touches.single { it.pin == binding }.feel)
    assertTrue(touches.filter { it.pin != binding }.all { it.feel == PinFeel.Springy })
  }

  @Test
  fun `the pin under the tip is the one selected, and between pins none is`() {
    sim.hold(2, 0f)
    settle()
    assertEquals(2, sim.snapshot().selectedPin)
    sim.movePick((sim.keyway.depthOf(2) + sim.keyway.depthOf(1)) / 2f, 0f)
    settle()
    assertEquals(-1, sim.snapshot().selectedPin)
  }

  @Test
  fun `lifting between two pins knocks on the land and goes no higher`() {
    val land = (sim.keyway.depthOf(2) + sim.keyway.depthOf(1)) / 2f
    sim.movePick(land, 0f)
    settle()
    events.clear()
    sim.movePick(land, 0.03f)
    settle()
    sim.movePick(land, 0.04f)
    settle()
    assertEquals("said once per knock", listOf<LockEvent>(LockEvent.WardHit), events)
    assertEquals(testGeometry.pinRestY - 0.002f + Keyway.LAND_CLEARANCE, sim.pose.pickY, 1e-4f)
    assertTrue("no pin was moved", (0 until testGeometry.pinCount).all { sim.readout.keyLift(it) == 0f })
    // Relax and lift again: it knocks again.
    sim.movePick(land, 0f)
    settle()
    sim.movePick(land, 0.03f)
    settle()
    assertEquals(2, events.count { it == LockEvent.WardHit })
  }

  @Test
  fun `dragging a lifted pick over the pins scrapes on the lands without a fresh knock at each`() {
    sim.hold(3, 0f)
    settle()
    events.clear()
    slide(sim.keyway.depthOf(3), sim.keyway.depthOf(0), lift = 0.02f, frames = 120)
    assertTrue(events.any { it == LockEvent.WardScrape })
    assertTrue("the tip rides from pin to land: it is not driven up into it", events.none { it == LockEvent.WardHit })
    assertEquals("and each pin is felt as it comes", listOf(2, 1, 0), events.filterIsInstance<LockEvent.PinTouched>().map { it.pin })
  }

  @Test
  fun `raking a lifted pick under tension sets the binding pin when the tip gets to it`() {
    pull(0.5f)
    val binding = sim.snapshot().bindingPin
    val shear = testGeometry.pinSpecs[binding].liftToShear
    slide(0f, sim.keyway.depthOf(binding), lift = shear, frames = 90)
    assertEquals(PinState.Set, sim.readout.state(binding))
  }

  @Test
  fun `the ends of the keyway are felt once each time the pick is pushed into them`() {
    sim.movePick(0.9f, 0f)
    settle()
    sim.movePick(1.05f, 0f)
    sim.movePick(1.06f, 0f)
    assertEquals(1, events.count { it == LockEvent.KeywayEnd })
    assertEquals(1f, sim.pickDepthNow, 0f)
    sim.movePick(0.9f, 0f)
    sim.movePick(1.05f, 0f)
    assertEquals(2, events.count { it == LockEvent.KeywayEnd })
    sim.movePick(-0.05f, 0f)
    assertEquals(3, events.count { it == LockEvent.KeywayEnd })
  }

  @Test
  fun `a relaxed hand drops the tip but leaves the pick where it is`() {
    sim.hold(1, 0.02f)
    settle()
    assertEquals(0.02f, sim.readout.keyLift(1), 1e-4f)
    sim.letGo()
    settle()
    assertEquals(0f, sim.readout.keyLift(1), 0f)
    assertEquals(sim.keyway.depthOf(1), sim.pickDepthNow, 0f)
    assertEquals(testGeometry.pinZ[1], sim.pose.pickZ, 1e-4f)
    assertEquals(1, sim.snapshot().selectedPin)
  }

  @Test
  fun `the lock opens with nothing but a wrench and a pick moved by hand`() {
    pull(EASED)
    repeat(2 * testGeometry.pinCount) {
      val binding = sim.snapshot().bindingPin
      if (binding < 0) return@repeat
      slide(sim.pickDepthNow, sim.keyway.depthOf(binding), frames = 20)
      sim.movePick(sim.pickDepthNow, testGeometry.pinSpecs[binding].liftToShear + 0.002f)
      settle()
      sim.letGo()
      settle()
    }
    assertTrue(sim.snapshot().opened)
    assertEquals(LockEvent.Opened, milestones.last())
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

  private companion object {
    const val FRAME = 1f / 60f
    const val MAX_FRAMES = 1200

    /** Light enough that a spool turns the plug back without help. */
    const val EASED = 0.15f
  }
}
