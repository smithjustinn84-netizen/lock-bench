package com.lockpicking.bench.domain.game

import com.lockpicking.bench.domain.lock.LockEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class GameRoundTest {
  private fun round(tier: GameTier = GameTier.Apprentice, seed: Int = 7, previous: List<Int>? = null) =
    GameRound(tier, pinCount = 4, random = Random(seed), previousOrder = previous)

  @Test
  fun `the binding order is a seeded shuffle of every pin`() {
    val a = round(seed = 3).bindingOrder
    assertEquals(a, round(seed = 3).bindingOrder)
    assertEquals(listOf(0, 1, 2, 3), a.sorted())
    val orders = (0 until 50).map { round(seed = it).bindingOrder }.toSet()
    assertTrue("deals more than a couple of locks", orders.size > 10)
  }

  @Test
  fun `two locks in a row never bind the same way`() {
    for (seed in 0 until 50) {
      val first = round(seed = seed).bindingOrder
      assertNotEquals(first, round(seed = seed, previous = first).bindingOrder)
    }
  }

  @Test
  fun `the clock waits for the first pull, pauses, and stops on open`() {
    val r = round()
    assertEquals(0L, r.elapsed(5_000))
    r.start(1_000)
    r.start(2_000)
    assertEquals(4_000L, r.elapsed(5_000))
    r.pause(5_000)
    assertEquals(4_000L, r.elapsed(60_000))
    r.resume(60_000)
    assertEquals(5_000L, r.elapsed(61_000))
    assertTrue(r.onEvent(LockEvent.Opened, 61_000))
    assertEquals(5_000L, r.elapsed(90_000))
  }

  @Test
  fun `mistakes count only while the clock runs`() {
    val r = round()
    r.onEvent(LockEvent.Dropped, 0)
    assertEquals("nothing counts before the first pull", 0, r.mistakes)
    r.start(0)
    r.onEvent(LockEvent.Dropped, 1)
    r.onEvent(LockEvent.PinOverset(2), 2)
    r.onEvent(LockEvent.TensionBite(heavy = true), 3)
    r.onEvent(LockEvent.TensionBite(heavy = false), 4)
    r.onEvent(LockEvent.PinSet(0, 1), 5)
    assertEquals(1, r.drops)
    assertEquals(1, r.oversets)
    assertEquals(1, r.seizes)
    r.onEvent(LockEvent.Opened, 6)
    r.onEvent(LockEvent.Dropped, 7)
    assertEquals("nothing counts once open", 3, r.mistakes)
  }

  @Test
  fun `stars reward a clean pick under par`() {
    fun stars(mistakes: Int, seconds: Int): Int {
      val r = round(GameTier.Journeyman)
      r.start(0)
      repeat(mistakes) { r.onEvent(LockEvent.Dropped, 0) }
      r.onEvent(LockEvent.Opened, seconds * 1000L)
      return r.result(previousBestMillis = null).stars
    }
    val par = GameTier.Journeyman.parSeconds
    assertEquals(3, stars(0, par))
    assertEquals(2, stars(0, par + 1))
    assertEquals(2, stars(2, 10))
    assertEquals(1, stars(3, 10))
  }

  @Test
  fun `a result knows whether it beat the best`() {
    val r = round()
    r.start(0)
    r.onEvent(LockEvent.Opened, 20_000)
    assertTrue(r.result(previousBestMillis = null).newBest)
    assertTrue(r.result(previousBestMillis = 25_000).newBest)
    assertFalse(r.result(previousBestMillis = 15_000).newBest)
  }
}
