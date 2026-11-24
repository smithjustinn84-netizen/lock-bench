package com.lockpicking.bench.domain.lock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SerratedPinTest {
  private val events = mutableListOf<LockEvent>()

  // Pin 1 (index 0) is serrated and binds first; pin 2 is plain.
  private val specs = listOf(
    PinSpec(1, liftToShear = 0.040f, springRest = 0.160f, serrated = true),
    PinSpec(2, liftToShear = 0.030f, springRest = 0.150f),
  )
  private val lock = LockMechanics(specs, bindingOrder = listOf(0, 1), onEvent = { events += it })

  private val clicks get() = events.filterIsInstance<LockEvent.SerrationClick>()

  /** The lift at which groove [notch] (1 = the first one met) reaches the shear line. */
  private fun grooveAt(notch: Int) =
    specs[0].liftToShear - (LockMechanics.SERRATIONS - notch + 1) * LockMechanics.SERRATION_PITCH

  @Test
  fun `a driver is a spool or serrated, never both`() {
    val both = runCatching { PinSpec(1, liftToShear = 0.03f, springRest = 0.15f, spool = true, serrated = true) }
    assertTrue(both.isFailure)
  }

  @Test
  fun `under light torque every groove clicks on the way up and the pin is not set until the last`() {
    lock.applyTorque(0.3f)
    lock.press(0, specs[0].liftToShear - 0.001f)
    assertEquals(listOf(1, 2, 3), clicks.map { it.notch })
    assertEquals(PinState.Resting, lock.state(0))
    assertEquals(0, lock.setCount)

    lock.press(0, specs[0].liftToShear)
    assertEquals(PinState.Set, lock.state(0))
    assertEquals(3, clicks.size)
  }

  @Test
  fun `one fast push still clicks through the grooves before it sets`() {
    lock.applyTorque(0.3f)
    lock.press(0, specs[0].liftToShear)
    assertEquals(3, clicks.size)
    assertTrue(events.indexOfFirst { it is LockEvent.PinSet } > events.indexOfLast { it is LockEvent.SerrationClick })
  }

  @Test
  fun `under firm torque the first groove catches and holds the pin short of the set`() {
    lock.applyTorque(0.6f)
    lock.press(0, specs[0].liftToShear, wanted = specs[0].liftToShear)
    assertEquals(grooveAt(1), lock.keyLift(0), 1e-6f)
    assertEquals(listOf(1), clicks.map { it.notch })
    assertEquals(PinState.Resting, lock.state(0))
    // It clicked like a set, but the same pin is still the one binding.
    assertEquals(0, lock.bindingPin)

    // The push that put it there already says it is held, and pushing on says nothing more.
    assertEquals(1, events.count { it is LockEvent.SerrationHeld })
    assertEquals(0, lock.blockedPin)
    repeat(3) { lock.press(0, specs[0].liftToShear, wanted = specs[0].liftToShear) }
    assertEquals(grooveAt(1), lock.keyLift(0), 1e-6f)
    assertEquals(1, events.count { it is LockEvent.SerrationHeld })
  }

  @Test
  fun `the catch is not reported before the pin has reached the groove`() {
    lock.applyTorque(0.6f)
    lock.press(0, grooveAt(1) - 0.004f, wanted = specs[0].liftToShear)
    assertFalse(events.any { it is LockEvent.SerrationHeld })
    assertEquals(-1, lock.blockedPin)
  }

  @Test
  fun `easing the wrench lets a caught pin through to the set`() {
    lock.applyTorque(0.6f)
    lock.press(0, specs[0].liftToShear, wanted = specs[0].liftToShear)
    lock.applyTorque(LockMechanics.T_SERRATION)
    lock.press(0, specs[0].liftToShear, wanted = specs[0].liftToShear)
    assertEquals(PinState.Set, lock.state(0))
    assertEquals(listOf(1, 2, 3), clicks.map { it.notch })
    assertEquals(-1, lock.blockedPin)
  }

  @Test
  fun `tightening up again part way catches on the next groove`() {
    lock.applyTorque(0.3f)
    lock.press(0, grooveAt(1) + 0.002f)
    lock.applyTorque(0.6f)
    lock.press(0, specs[0].liftToShear, wanted = specs[0].liftToShear)
    assertEquals(grooveAt(2), lock.keyLift(0), 1e-6f)
  }

  @Test
  fun `a serrated pin that is not binding rides its spring in silence`() {
    val other = LockMechanics(specs, bindingOrder = listOf(1, 0), onEvent = { events += it })
    other.applyTorque(0.6f)
    other.press(0, specs[0].liftToShear - 0.001f)
    assertTrue(clicks.isEmpty())
    assertEquals(specs[0].liftToShear - 0.001f, other.keyLift(0), 1e-6f)
  }

  @Test
  fun `without torque nothing catches`() {
    lock.press(0, lock.maxLift(0))
    assertTrue(clicks.isEmpty())
    assertEquals(lock.maxLift(0), lock.keyLift(0), 1e-6f)
  }
}
