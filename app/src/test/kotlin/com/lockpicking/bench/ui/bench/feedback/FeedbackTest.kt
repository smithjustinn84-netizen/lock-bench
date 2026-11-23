package com.lockpicking.bench.ui.bench.feedback

import com.lockpicking.bench.domain.lock.LockEvent
import com.lockpicking.bench.domain.lock.LockMechanics
import com.lockpicking.bench.domain.lock.PinFeel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedbackTest {

  @Test
  fun `a set lands harder the harder the wrench is pulled`() {
    val light = cueFor(LockEvent.PinSet(0, 1), torque = 0.1f)
    val firm = cueFor(LockEvent.PinSet(0, 1), torque = 0.6f)
    assertEquals(Haptic.Click, light.haptic)
    assertEquals(LockSound.SetClick, light.sound)
    assertTrue(firm.strength > light.strength)
    assertTrue("even the lightest set can be felt", light.strength >= 0.4f)
  }

  @Test
  fun `a false set is a different thing in the hand from a set`() {
    val set = cueFor(LockEvent.PinSet(0, 1), 0.5f)
    val falseSet = cueFor(LockEvent.FalseSet(2), 0.5f)
    assertEquals(Haptic.Thud, falseSet.haptic)
    assertEquals(LockSound.FalseSetClunk, falseSet.sound)
    assertTrue(falseSet.haptic != set.haptic && falseSet.sound != set.sound)
  }

  @Test
  fun `the last notch of counter-rotation is the firm one`() {
    val early = cueFor(LockEvent.CounterRotation(2, 1), 0.2f)
    val last = cueFor(LockEvent.CounterRotation(2, LockMechanics.COUNTER_ROTATION_STEPS), 0.2f)
    assertEquals(Haptic.Tick, early.haptic)
    assertTrue(last.strength > early.strength)
  }

  @Test
  fun `the scrape of a pin is faint under light torque`() {
    assertTrue(cueFor(LockEvent.Grit(0), 0.1f).strength < 0.3f)
    assertEquals(1f, cueFor(LockEvent.Grit(0), 0.75f).strength, 0f)
  }

  @Test
  fun `every event has a sound and strength in range`() {
    val events = listOf(
      LockEvent.PinSet(0, 1), LockEvent.PinOverset(0), LockEvent.OversetReleased(0), LockEvent.FalseSet(0),
      LockEvent.CounterRotation(0, 1), LockEvent.SpoolBlocked(0), LockEvent.Seized(0), LockEvent.Grit(0),
      LockEvent.Dropped, LockEvent.Opened, LockEvent.Relocked,
    )
    for (event in events) {
      for (torque in listOf(0f, 0.3f, 1f)) {
        val cue = cueFor(event, torque)
        assertTrue("$event at $torque", cue.sound != null && cue.strength in 0f..1f)
      }
    }
    assertNull("the shackle closing is heard, not felt", cueFor(LockEvent.Relocked, 0f).haptic)
  }

  @Test
  fun `the tip finds the binding pin by how hard it pushes back`() {
    val binding = cueFor(LockEvent.PinTouched(0, PinFeel.Binding), 0.4f)
    val springy = cueFor(LockEvent.PinTouched(1, PinFeel.Springy), 0.4f)
    val loose = cueFor(LockEvent.PinTouched(2, PinFeel.Loose), 0.4f)
    assertTrue(listOf(binding, springy, loose).all { it.haptic == Haptic.Bump })
    assertTrue(binding.strength > springy.strength * 1.5f)
    assertTrue(springy.strength > loose.strength * 1.5f)
    assertTrue(cueFor(LockEvent.PinTouched(0, PinFeel.Binding), 0.7f).strength > binding.strength)
  }

  @Test
  fun `metal is a knock, a spring is barely there, and neither is a set`() {
    val ward = cueFor(LockEvent.WardHit, 0.4f)
    val stop = cueFor(LockEvent.HardStop(0), 0.4f)
    val spring = cueFor(LockEvent.SpringTick(0, 0.5f), 0.4f)
    assertEquals(Haptic.Knock, ward.haptic)
    assertEquals(Haptic.Knock, stop.haptic)
    assertTrue(ward.strength >= stop.strength)
    assertEquals(Haptic.Spring, spring.haptic)
    assertNull("a spring giving makes no sound", spring.sound)
    assertTrue(spring.strength < 0.5f)
    assertTrue(cueFor(LockEvent.SpringTick(0, 0.9f), 0.4f).strength > spring.strength)
  }

  @Test
  fun `what the tip drags over is a texture`() {
    val textures = listOf(LockEvent.Grit(0), LockEvent.SpringTick(0, 0.5f), LockEvent.WardScrape)
    assertTrue(textures.all { cueFor(it, 0.4f).haptic!!.isTexture })
    assertTrue(listOf(LockEvent.PinSet(0, 1), LockEvent.WardHit, LockEvent.PinTouched(0, PinFeel.Binding)).none { cueFor(it, 0.4f).haptic!!.isTexture })
  }
}
