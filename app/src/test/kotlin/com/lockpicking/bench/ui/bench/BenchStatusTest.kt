package com.lockpicking.bench.ui.bench

import com.lockpicking.bench.domain.lock.TorqueBand
import com.lockpicking.bench.testing.american1100Geometry
import org.junit.Assert.assertEquals
import org.junit.Test

class BenchStatusTest {

  @Test
  fun `without tension the lock reads locked`() {
    assertEquals(BenchStatus.Locked, BenchUiState().status())
  }

  @Test
  fun `with tension it shows progress and names the binding pin`() {
    val state = BenchUiState(band = TorqueBand.Firm, setCount = 2, bindingPin = 3)
    assertEquals(BenchStatus.Picking(setCount = 2, pinCount = 4, bindingPinNumber = 4), state.status())
  }

  @Test
  fun `with tension and no binding pin there is no hint`() {
    val state = BenchUiState(band = TorqueBand.Firm, bindingPin = -1)
    assertEquals(BenchStatus.Picking(setCount = 0, pinCount = 4, bindingPinNumber = null), state.status())
  }

  @Test
  fun `a blocked pin reads as caught on a serrated driver and as ease off on a spool`() {
    val state = BenchUiState(geometry = american1100Geometry, band = TorqueBand.Firm)
    assertEquals(BenchStatus.Caught(pinNumber = 1), state.copy(blockedPin = 0).status())
    assertEquals(BenchStatus.EaseOff(pinNumber = 2), state.copy(blockedPin = 1).status())
  }

  @Test
  fun `an overset pin wins over progress`() {
    val state = BenchUiState(band = TorqueBand.Firm, setCount = 1, oversetPin = 0)
    assertEquals(BenchStatus.Overset(pinNumber = 1), state.status())
  }

  @Test
  fun `a false set names the spool the plug is hanging on`() {
    val state = BenchUiState(band = TorqueBand.Firm, setCount = 2, bindingPin = 2, falseSetPin = 2)
    assertEquals(BenchStatus.FalseSet(pinNumber = 3), state.status())
  }

  @Test
  fun `pushing a blocked spool says to ease off`() {
    val state = BenchUiState(band = TorqueBand.Firm, setCount = 2, falseSetPin = 2, blockedPin = 2)
    assertEquals(BenchStatus.EaseOff(pinNumber = 3), state.status())
  }

  @Test
  fun `heavy torque reads as seized`() {
    val state = BenchUiState(band = TorqueBand.Heavy, bindingPin = 0, blockedPin = 0)
    assertEquals(BenchStatus.Seized, state.status())
  }

  @Test
  fun `with hints off the status only counts sets`() {
    val picking = BenchStatus.Picking(setCount = 2, pinCount = 4, bindingPinNumber = null)
    assertEquals(picking, BenchUiState(hints = false, band = TorqueBand.Firm, setCount = 2, bindingPin = 2, falseSetPin = 2).status())
    assertEquals(picking, BenchUiState(hints = false, band = TorqueBand.Heavy, setCount = 2, oversetPin = 1).status())
    assertEquals(BenchStatus.Locked, BenchUiState(hints = false).status())
  }

  @Test
  fun `open wins over everything`() {
    val state = BenchUiState(opened = true, band = TorqueBand.Firm, oversetPin = 2)
    assertEquals(BenchStatus.Open, state.status())
  }
}
