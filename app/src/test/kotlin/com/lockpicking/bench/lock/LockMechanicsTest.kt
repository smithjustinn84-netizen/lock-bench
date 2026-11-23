package com.lockpicking.bench.lock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LockMechanicsTest {
  private val events = mutableListOf<LockEvent>()
  private val specs = listOf(
    PinSpec(1, liftToShear = 0.032f, springRest = 0.152f),
    PinSpec(2, liftToShear = 0.048f, springRest = 0.168f, spool = true),
    PinSpec(3, liftToShear = 0.022f, springRest = 0.142f, spool = true),
    PinSpec(4, liftToShear = 0.040f, springRest = 0.160f),
  )
  private val lock = LockMechanics(specs, onEvent = { events += it })

  private fun nudgeToShear(pin: Int) {
    lock.press(pin, specs[pin].liftToShear)
    lock.release()
  }

  private fun settle() = repeat(120) { lock.step(1f / 60f) }

  @Test
  fun `without tension a lifted pin just falls back`() {
    nudgeToShear(2)
    assertEquals(PinState.Resting, lock.states[2])
    settle()
    assertEquals(0f, lock.keyLift[2], 0f)
    assertEquals(0f, lock.driverLift[2], 0f)
    assertTrue(events.isEmpty())
    assertFalse(lock.isSettling)
  }

  @Test
  fun `only the binding pin sets`() {
    lock.applyTension(true)
    assertEquals(2, lock.bindingPin)
    nudgeToShear(0)
    assertEquals(PinState.Resting, lock.states[0])
    nudgeToShear(2)
    assertEquals(PinState.Set, lock.states[2])
    assertEquals(listOf<LockEvent>(LockEvent.PinSet(2, 1)), events)
    assertEquals(0, lock.bindingPin)
    assertEquals(LockMechanics.SET_DEGREES, lock.plugDegrees, 0f)
  }

  @Test
  fun `a set driver stays on the ledge while its key pin drops`() {
    lock.applyTension(true)
    nudgeToShear(2)
    settle()
    assertEquals(0f, lock.keyLift[2], 0f)
    assertEquals(specs[2].liftToShear, lock.driverLift[2], 0f)
  }

  @Test
  fun `picking in binding order opens the lock`() {
    lock.applyTension(true)
    for (pin in lock.bindingOrder) nudgeToShear(pin)
    assertTrue(lock.opened)
    assertEquals(LockEvent.Opened, events.last())
    assertEquals(LockMechanics.OPEN_DEGREES, lock.plugDegrees, 0f)
    assertEquals(-1, lock.bindingPin)
  }

  @Test
  fun `pushing on past the set oversets and blocks progress`() {
    lock.applyTension(true)
    val shear = specs[2].liftToShear
    lock.press(2, shear)
    lock.press(2, shear + LockMechanics.OVERSET_MARGIN + 0.004f)
    lock.release()
    assertEquals(PinState.Overset, lock.states[2])
    assertEquals(LockEvent.PinOverset(2), events.last())
    assertEquals(2, lock.bindingPin)
    settle()
    assertTrue("an overset pin stays jammed", lock.keyLift[2] > shear)
    nudgeToShear(0)
    assertEquals(PinState.Resting, lock.states[0])
  }

  @Test
  fun `one fast drag across the shear line sets rather than oversets`() {
    lock.applyTension(true)
    lock.press(2, lock.maxLift(2))
    assertEquals(PinState.Set, lock.states[2])
  }

  @Test
  fun `releasing tension drops every pin`() {
    lock.applyTension(true)
    nudgeToShear(2)
    nudgeToShear(0)
    lock.applyTension(false)
    assertEquals(LockEvent.Dropped, events.last())
    assertTrue(lock.states.all { it == PinState.Resting })
    settle()
    assertTrue(lock.driverLift.all { it == 0f })
    assertEquals(0f, lock.plugDegrees, 0f)
  }

  @Test
  fun `relock returns an open lock to rest`() {
    lock.applyTension(true)
    for (pin in lock.bindingOrder) nudgeToShear(pin)
    lock.relock()
    assertFalse(lock.opened)
    assertFalse(lock.tension)
    assertEquals(LockEvent.Relocked, events.last())
    settle()
    assertTrue(lock.driverLift.all { it == 0f })
  }
}
