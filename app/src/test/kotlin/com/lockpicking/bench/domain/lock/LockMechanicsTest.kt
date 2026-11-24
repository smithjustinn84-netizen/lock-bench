package com.lockpicking.bench.domain.lock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LockMechanicsTest {
  private val events = mutableListOf<LockEvent>()

  // Pins 1 and 4 are standard. Pin 3 (index 2) is a spool whose waist is at the shear line at
  // rest; pin 2 (index 1) is a spool resting on its upper flange, with the waist 0.5 mm above.
  private val specs = listOf(
    PinSpec(1, liftToShear = 0.032f, springRest = 0.152f),
    PinSpec(2, liftToShear = 0.048f, springRest = 0.168f, spool = true),
    PinSpec(3, liftToShear = 0.022f, springRest = 0.142f, spool = true),
    PinSpec(4, liftToShear = 0.040f, springRest = 0.160f),
  )
  private val lock = LockMechanics(specs, onEvent = { events += it })

  /** What the lock did, without what the hands only feel: the wrench biting and springs giving. */
  private val milestones get() = events.filterNot { it is LockEvent.SpringTick || it is LockEvent.TensionBite }

  private fun nudgeToShear(pin: Int) {
    lock.press(pin, specs[pin].liftToShear)
    lock.release()
  }

  private fun settle() = repeat(120) { lock.step(1f / 60f) }

  /** Rotation from sets and false sets alone, without the elastic wind-up of the torque. */
  private fun turned() = lock.plugDegrees - lock.torque * LockMechanics.WINDUP_DEGREES

  /** Sets the two standard pins, which leaves both spools and nothing else holding the plug. */
  private fun setStandardPins() {
    nudgeToShear(0)
    nudgeToShear(3)
    check(lock.setCount == 2)
    events.clear()
  }

  /** Lifts pin 2 off its upper flange, so the plug can drop into both waists. */
  private fun dropIntoFalseSet() {
    setStandardPins()
    lock.press(1, 0.010f)
    lock.release()
    settle()
    check(lock.falseSetDepth == 1f)
  }

  // ── Torque ──────────────────────────────────────────────────────────────────────────────────

  @Test
  fun `without torque a lifted pin just falls back`() {
    nudgeToShear(0)
    assertEquals(PinState.Resting, lock.state(0))
    settle()
    assertEquals(0f, lock.keyLift(0), 0f)
    assertEquals(0f, lock.driverLift(0), 0f)
    assertTrue(milestones.isEmpty())
    assertFalse(lock.isSettling)
  }

  @Test
  fun `the plug engages at the binding torque and lets go below the drop torque`() {
    lock.applyTorque(LockMechanics.T_BIND - 0.01f)
    assertFalse(lock.engaged)
    assertEquals(TorqueBand.Slack, lock.band)
    lock.applyTorque(LockMechanics.T_BIND)
    assertTrue(lock.engaged)
    // Between the two thresholds a trembling hand keeps what it has.
    lock.applyTorque(LockMechanics.T_DROP + 0.01f)
    assertTrue(lock.engaged)
    lock.applyTorque(LockMechanics.T_DROP - 0.01f)
    assertFalse(lock.engaged)
  }

  @Test
  fun `the band follows the torque`() {
    lock.applyTorque(0.15f)
    assertEquals(TorqueBand.Eased, lock.band)
    lock.applyTorque(0.5f)
    assertEquals(TorqueBand.Firm, lock.band)
    lock.applyTorque(0.8f)
    assertEquals(TorqueBand.Heavy, lock.band)
    lock.applyTorque(0.72f)
    assertEquals("still seized until the torque is well clear", TorqueBand.Heavy, lock.band)
    lock.applyTorque(0.65f)
    assertEquals(TorqueBand.Firm, lock.band)
  }

  @Test
  fun `only the binding pin sets`() {
    lock.applyTorque(0.5f)
    assertEquals("a standard pin binds before a spool that has its waist at the shear line", 0, lock.bindingPin)
    nudgeToShear(3)
    assertEquals(PinState.Resting, lock.state(3))
    nudgeToShear(0)
    assertEquals(PinState.Set, lock.state(0))
    assertEquals(listOf<LockEvent>(LockEvent.PinSet(0, 1)), milestones)
    assertEquals(3, lock.bindingPin)
    assertEquals(LockMechanics.SET_DEGREES, turned(), 1e-4f)
  }

  @Test
  fun `light torque still sets a standard pin`() {
    lock.applyTorque(0.10f)
    nudgeToShear(0)
    assertEquals(PinState.Set, lock.state(0))
  }

  @Test
  fun `a set driver stays on the ledge while its key pin drops`() {
    lock.applyTorque(0.5f)
    nudgeToShear(0)
    settle()
    assertEquals(0f, lock.keyLift(0), 0f)
    assertEquals(specs[0].liftToShear, lock.driverLift(0), 0f)
  }

  @Test
  fun `picking the binding pin each time opens the lock`() {
    lock.applyTorque(0.15f)
    repeat(specs.size) { nudgeToShear(lock.bindingPin) }
    assertTrue(lock.opened)
    assertEquals(LockEvent.Opened, events.last())
    assertEquals(LockMechanics.OPEN_DEGREES, lock.plugDegrees, 0f)
    assertEquals(-1, lock.bindingPin)
  }

  @Test
  fun `the binding pin drags more the harder the wrench is pulled`() {
    lock.applyTorque(0.1f)
    val light = lock.liftRate(0)
    lock.applyTorque(0.6f)
    val firm = lock.liftRate(0)
    assertTrue(firm < light)
    assertEquals("a pin that is not binding moves freely", LockMechanics.LIFT_RATE_FREE, lock.liftRate(3), 0f)
  }

  @Test
  fun `heavy torque seizes the binding pin`() {
    lock.applyTorque(0.8f)
    lock.press(0, 0.010f)
    assertEquals(0f, lock.keyLift(0), 0f)
    assertEquals(0, lock.blockedPin)
    lock.press(0, 0.020f)
    assertEquals("said once per push", listOf<LockEvent>(LockEvent.Seized(0)), milestones)
    // A pin that is not binding still moves.
    lock.release()
    lock.press(3, 0.010f)
    assertEquals(0.010f, lock.keyLift(3), 0f)
  }

  @Test
  fun `a binding pin scrapes as it is lifted`() {
    lock.applyTorque(0.5f)
    var lift = 0f
    while (lift < 0.020f) {
      lift += 0.001f
      lock.press(0, lift)
    }
    assertEquals(4, events.count { it is LockEvent.Grit })
  }

  @Test
  fun `releasing the torque drops every pin`() {
    lock.applyTorque(0.5f)
    nudgeToShear(0)
    nudgeToShear(3)
    lock.applyTorque(0f)
    assertEquals(LockEvent.Dropped, events.last())
    assertTrue((0 until lock.pinCount).all { lock.state(it) == PinState.Resting })
    settle()
    assertTrue((0 until lock.pinCount).all { lock.driverLift(it) == 0f })
    assertEquals(0f, lock.plugDegrees, 0f)
  }

  @Test
  fun `relock returns an open lock to rest`() {
    lock.applyTorque(0.15f)
    repeat(specs.size) { nudgeToShear(lock.bindingPin) }
    lock.relock()
    assertFalse(lock.opened)
    assertFalse(lock.engaged)
    assertEquals(LockEvent.Relocked, events.last())
    settle()
    assertTrue((0 until lock.pinCount).all { lock.driverLift(it) == 0f })
  }

  // ── Overset ─────────────────────────────────────────────────────────────────────────────────

  @Test
  fun `pushing on past the set oversets and blocks progress`() {
    lock.applyTorque(0.5f)
    val shear = specs[0].liftToShear
    lock.press(0, shear)
    lock.press(0, shear + lock.oversetMargin() + 0.004f)
    lock.release()
    assertEquals(PinState.Overset, lock.state(0))
    assertEquals(LockEvent.PinOverset(0), events.last())
    assertEquals(0, lock.bindingPin)
    settle()
    assertTrue("an overset pin stays jammed", lock.keyLift(0) > shear)
    nudgeToShear(3)
    assertEquals(PinState.Resting, lock.state(3))
  }

  @Test
  fun `one fast drag across the shear line sets rather than oversets`() {
    lock.applyTorque(0.5f)
    lock.press(0, lock.maxLift(0))
    assertEquals(PinState.Set, lock.state(0))
  }

  @Test
  fun `heavier torque leaves less room before a pin oversets`() {
    lock.applyTorque(0.1f)
    val light = lock.oversetMargin()
    lock.applyTorque(0.7f)
    assertTrue(lock.oversetMargin() < light)
  }

  @Test
  fun `easing the torque frees an overset pin and keeps the other sets`() {
    lock.applyTorque(0.5f)
    nudgeToShear(0)
    val shear = specs[3].liftToShear
    lock.press(3, shear)
    lock.press(3, shear + lock.oversetMargin() + 0.004f)
    lock.release()
    assertEquals(PinState.Overset, lock.state(3))
    settle()
    assertEquals("firm torque keeps it jammed", PinState.Overset, lock.state(3))

    lock.applyTorque(0.15f)
    settle()
    assertEquals(PinState.Resting, lock.state(3))
    assertEquals(0f, lock.keyLift(3), 0f)
    assertEquals(PinState.Set, lock.state(0))
    assertEquals(LockEvent.OversetReleased(3), events.last())
    assertFalse(events.contains(LockEvent.Dropped))
  }

  // ── Spools ──────────────────────────────────────────────────────────────────────────────────

  @Test
  fun `there is no false set while a standard pin is unset`() {
    lock.applyTorque(0.5f)
    nudgeToShear(0)
    // Pin 3's waist is at the shear line, but pin 4 still holds the plug.
    lock.press(1, 0.010f)
    assertEquals(0f, lock.falseSetDepth, 0f)
    assertEquals(-1, lock.falseSetPin)
    assertFalse(events.any { it is LockEvent.FalseSet })
  }

  @Test
  fun `with the standard pins set the last spool binds on its flange`() {
    lock.applyTorque(0.5f)
    setStandardPins()
    assertEquals(1, lock.bindingPin)
    assertEquals(0f, lock.falseSetDepth, 0f)
    assertEquals(2 * LockMechanics.SET_DEGREES, turned(), 1e-4f)
  }

  @Test
  fun `lifting that spool off its flange drops the plug into a false set`() {
    lock.applyTorque(0.5f)
    setStandardPins()
    lock.press(1, 0.004f)
    assertEquals("still on the flange", 0f, lock.falseSetDepth, 0f)
    lock.press(1, 0.010f)
    assertEquals(1f, lock.falseSetDepth, 0f)
    assertEquals(1, events.count { it is LockEvent.FalseSet })
    assertEquals(2 * LockMechanics.SET_DEGREES + LockMechanics.FALSE_SET_DEGREES, turned(), 1e-4f)
    assertEquals("the plug now hangs on the tighter of the two spools", 2, lock.falseSetPin)
    assertEquals(1f, lock.driverTilt(1), 0f)
    assertEquals(0f, lock.driverTilt(0), 0f)
  }

  @Test
  fun `a spool in a false set hangs by its flange when the pick lets go`() {
    lock.applyTorque(0.5f)
    dropIntoFalseSet()
    assertEquals(0f, lock.keyLift(1), 0f)
    assertEquals("0.5 mm of flange and the shoulder above it", 0.0085f, lock.driverLift(1), 1e-5f)
    assertEquals(1f, lock.falseSetDepth, 0f)
    assertFalse(lock.isSettling)
  }

  @Test
  fun `firm torque stops a spool at its flange`() {
    lock.applyTorque(0.5f)
    dropIntoFalseSet()
    events.clear()
    nudgeToShear(2)
    assertEquals(PinState.Resting, lock.state(2))
    // 2.2 mm to the shear line, less the 1.7 mm flange and the 0.35 mm shoulder below it.
    assertEquals(0.0015f, lock.driverLift(2), 1e-5f)
    assertEquals(1f, lock.falseSetDepth, 0f)
    assertEquals(listOf<LockEvent>(LockEvent.SpoolBlocked(2)), events)
  }

  @Test
  fun `partly eased the pick turns the plug part of the way back`() {
    lock.applyTorque(0.30f)
    dropIntoFalseSet()
    lock.press(2, specs[2].liftToShear)
    assertEquals(0.5f, lock.falseSetDepth, 0.01f)
    assertEquals(2, lock.blockedPin)
    assertEquals(PinState.Resting, lock.state(2))
    assertEquals(listOf(1, 2), events.filterIsInstance<LockEvent.CounterRotation>().map { it.step })
  }

  @Test
  fun `easing the wrench with the pick held lets the spool through`() {
    lock.applyTorque(0.5f)
    dropIntoFalseSet()
    val shear = specs[2].liftToShear
    lock.press(2, shear)
    assertEquals(PinState.Resting, lock.state(2))

    lock.applyTorque(0.18f)
    events.clear()
    lock.press(2, shear)
    assertEquals(PinState.Set, lock.state(2))
    assertEquals(-1, lock.blockedPin)
    val ticks = events.filterIsInstance<LockEvent.CounterRotation>()
    assertEquals(listOf(1, 2, 3, 4), ticks.map { it.step })
    assertTrue(ticks.all { it.pin == 2 })
    assertTrue("the plug is turned back before the pin sets", events.indexOf(ticks.last()) < events.indexOf(LockEvent.PinSet(2, 3)))
  }

  @Test
  fun `counter-rotation turns the plug back to the binding angle and the set turns it on`() {
    lock.applyTorque(0.18f)
    dropIntoFalseSet()
    val shear = specs[2].liftToShear
    // On the flange, short of the shear line: the plug has been driven all the way back.
    lock.press(2, shear - 0.005f)
    assertEquals(0f, lock.falseSetDepth, 0f)
    assertEquals(2 * LockMechanics.SET_DEGREES, turned(), 1e-4f)
    // The other spool is no longer hung on the plug, and drops back onto its flange.
    repeat(10) { lock.step(1f / 60f) }
    assertEquals(0f, lock.driverLift(1), 0f)
    lock.press(2, shear)
    assertEquals(PinState.Set, lock.state(2))
    assertEquals(3 * LockMechanics.SET_DEGREES, turned(), 1e-4f)
  }

  @Test
  fun `letting go half way up the flange drops the plug back into the false set`() {
    lock.applyTorque(0.18f)
    setStandardPins()
    nudgeToShear(1)
    assertEquals("only pin 3 is left, and its waist is at the shear line", 2, lock.falseSetPin)
    lock.press(2, specs[2].liftToShear - 0.005f)
    assertEquals(0f, lock.falseSetDepth, 0f)
    events.clear()
    lock.release()
    settle()
    assertEquals(PinState.Resting, lock.state(2))
    assertEquals(1f, lock.falseSetDepth, 0f)
    assertEquals(1, events.count { it is LockEvent.FalseSet })
  }

  @Test
  fun `a spool creeps up its shoulder while it turns the plug back`() {
    lock.applyTorque(0.18f)
    dropIntoFalseSet()
    lock.press(2, 0.0015f)
    assertEquals(LockMechanics.LIFT_RATE_SHOULDER, lock.liftRate(2), 0f)
    lock.press(2, 0.010f)
    assertTrue("past the shoulder it is an ordinary binding pin", lock.liftRate(2) > LockMechanics.LIFT_RATE_SHOULDER)
  }

  @Test
  fun `the whole lock picks through both false sets`() {
    lock.applyTorque(0.18f)
    setStandardPins()
    // Pin 2 comes off its flange a frame at a time, as a hand would lift it.
    var lift = 0f
    while (lock.state(1) != PinState.Set) {
      lift += 0.001f
      lock.press(1, lift)
    }
    lock.release()
    assertEquals(
      "one for each spool: with one left the plug drops straight back into its waist",
      2,
      events.count { it is LockEvent.FalseSet },
    )
    assertEquals(2, lock.falseSetPin)
    lift = 0f
    while (!lock.opened) {
      lift += 0.001f
      lock.press(2, lift)
    }
    assertEquals(LockEvent.Opened, events.last())
    assertEquals(8, events.count { it is LockEvent.CounterRotation })
  }

  // ── Feel ────────────────────────────────────────────────────────────────────────────────────

  @Test
  fun `the wrench is felt taking up and again when it is pulled too hard`() {
    lock.applyTorque(0.05f)
    assertTrue(events.isEmpty())
    lock.applyTorque(0.3f)
    lock.applyTorque(0.5f)
    assertEquals(listOf<LockEvent>(LockEvent.TensionBite(heavy = false)), events)
    lock.applyTorque(0.8f)
    lock.applyTorque(0.9f)
    assertEquals(LockEvent.TensionBite(heavy = true), events.last())
    assertEquals(2, events.size)
  }

  @Test
  fun `a touch tells the binding pin from a springy one and from a set one`() {
    assertEquals("with no torque every pin is just on its spring", PinFeel.Springy, lock.feel(0))
    lock.applyTorque(0.5f)
    assertEquals(PinFeel.Binding, lock.feel(0))
    assertEquals(PinFeel.Springy, lock.feel(3))
    nudgeToShear(0)
    assertEquals(PinFeel.Loose, lock.feel(0))
    assertEquals(PinFeel.Binding, lock.feel(3))
  }

  @Test
  fun `a free pin gives on its spring, more firmly the further it is pushed`() {
    lock.applyTorque(0.5f)
    var lift = 0f
    while (lift < 0.03f) {
      lift += 0.001f
      lock.press(3, lift)
    }
    val ticks = events.filterIsInstance<LockEvent.SpringTick>()
    assertEquals(4, ticks.size)
    assertTrue(ticks.all { it.pin == 3 })
    assertEquals(ticks.sortedBy { it.compression }, ticks)
    assertTrue(ticks.first().compression < ticks.last().compression)
    assertTrue("a spring does not scrape", events.none { it is LockEvent.Grit })
  }

  @Test
  fun `a binding pin scrapes and does not feel like a spring`() {
    lock.applyTorque(0.5f)
    var lift = 0f
    while (lift < 0.02f) {
      lift += 0.001f
      lock.press(0, lift)
    }
    assertTrue(events.any { it is LockEvent.Grit })
    assertTrue(events.none { it is LockEvent.SpringTick })
  }

  @Test
  fun `a set pin is loose up to the shear line and then solid`() {
    lock.applyTorque(0.5f)
    nudgeToShear(0)
    events.clear()
    lock.press(0, 0.020f)
    assertTrue("no spring behind it and nothing to scrape on", events.isEmpty())
    lock.press(0, 0.045f)
    lock.press(0, 0.050f)
    assertEquals("said once per push", listOf<LockEvent>(LockEvent.HardStop(0)), events)
    assertEquals(specs[0].liftToShear, lock.keyLift(0), 0f)
    // Back off and push again: it knocks again.
    lock.press(0, 0.020f)
    lock.press(0, 0.045f)
    assertEquals(2, events.count { it is LockEvent.HardStop })
  }

  @Test
  fun `the top of a chamber is a hard stop that easing the wrench will not help`() {
    lock.applyTorque(0.5f)
    val top = lock.maxLift(3)
    lock.press(3, top, wanted = top + 0.01f)
    lock.press(3, top, wanted = top + 0.02f)
    assertEquals(listOf<LockEvent>(LockEvent.HardStop(3)), milestones)
    assertEquals(-1, lock.blockedPin)
  }

  @Test
  fun `an overset is one event, not a jam and then a knock`() {
    lock.applyTorque(0.5f)
    lock.press(0, specs[0].liftToShear)
    lock.press(0, lock.maxLift(0))
    lock.press(0, lock.maxLift(0), wanted = lock.maxLift(0) + 0.02f)
    assertEquals(PinState.Overset, lock.state(0))
    assertTrue(events.none { it is LockEvent.HardStop })
  }

  @Test
  fun `dropping the torque in a false set drops everything`() {
    lock.applyTorque(0.5f)
    dropIntoFalseSet()
    lock.applyTorque(0f)
    assertEquals(LockEvent.Dropped, events.last())
    assertEquals(0f, lock.falseSetDepth, 0f)
    settle()
    assertTrue((0 until lock.pinCount).all { lock.driverLift(it) == 0f })
    assertFalse(lock.isSettling)
  }
}
