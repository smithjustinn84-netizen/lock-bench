package com.lockpicking.bench.ui.bench

import org.junit.Assert.assertEquals
import org.junit.Test

class BenchStatusTest {

  @Test
  fun `without tension the lock reads locked`() {
    assertEquals(BenchStatus.Locked, BenchUiState().status())
  }

  @Test
  fun `with tension it shows progress and names the binding pin`() {
    val state = BenchUiState(tension = true, setCount = 2, bindingPin = 3)
    assertEquals(BenchStatus.Picking(setCount = 2, pinCount = 4, bindingPinNumber = 4), state.status())
  }

  @Test
  fun `with tension and no binding pin there is no hint`() {
    val state = BenchUiState(tension = true, bindingPin = -1)
    assertEquals(BenchStatus.Picking(setCount = 0, pinCount = 4, bindingPinNumber = null), state.status())
  }

  @Test
  fun `an overset pin wins over progress`() {
    val state = BenchUiState(tension = true, setCount = 1, oversetPin = 0)
    assertEquals(BenchStatus.Overset(pinNumber = 1), state.status())
  }

  @Test
  fun `open wins over everything`() {
    val state = BenchUiState(opened = true, tension = true, oversetPin = 2)
    assertEquals(BenchStatus.Open, state.status())
  }
}
