package com.lockpicking.bench.ui.bench

import com.lockpicking.bench.data.LockAssetRepository
import com.lockpicking.bench.data.glb.GlbInfo
import com.lockpicking.bench.domain.lock.LockEvent
import com.lockpicking.bench.domain.lock.PinState
import com.lockpicking.bench.domain.motion.FrameClock
import com.lockpicking.bench.testing.bindingOrder
import com.lockpicking.bench.testing.testGeometry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LockBenchViewModelTest {
  private val dispatcher = StandardTestDispatcher()
  private val modelInfo = GlbInfo(triangles = 1200, primitives = 18, nodes = 20, trianglesByNode = emptyMap(), baseColors = emptyMap())

  private class FakeRepository(private val result: () -> GlbInfo) : LockAssetRepository {
    override suspend fun loadModelInfo(): GlbInfo = result()
  }

  @Before
  fun setUp() = Dispatchers.setMain(dispatcher)

  @After
  fun tearDown() = Dispatchers.resetMain()

  private fun viewModel(result: () -> GlbInfo = { modelInfo }) = LockBenchViewModel(FakeRepository(result))

  /** A 60 fps display on the test scheduler's virtual clock. */
  private fun TestScope.frameClock() = FrameClock {
    delay(FRAME_MILLIS)
    testScheduler.currentTime * 1_000_000L
  }

  /** Binds the model and starts the frame loop, as the scene does. Cancel the job to tear the scene down. */
  private fun TestScope.startScene(vm: LockBenchViewModel): Job {
    vm.onModelBound(testGeometry)
    return backgroundScope.launch { vm.runFrameLoop(frameClock()) { _, _ -> } }
  }

  private fun TestScope.bound(vm: LockBenchViewModel): LockBenchViewModel = vm.also { startScene(it) }

  /** Lets the frame loop play out [seconds] of virtual time. */
  private fun TestScope.play(seconds: Float = 2f) = advanceTimeBy((seconds * 1000).toLong())

  @Test
  fun `loads the model info off the screen`() = runTest(dispatcher) {
    val vm = viewModel()
    assertNull(vm.uiState.value.modelInfo)
    advanceUntilIdle()
    assertSame(modelInfo, vm.uiState.value.modelInfo)
  }

  @Test
  fun `a failed load surfaces its message`() = runTest(dispatcher) {
    val vm = viewModel { error("disk on fire") }
    advanceUntilIdle()
    assertEquals("disk on fire", vm.uiState.value.loadError)
  }

  @Test
  fun `pin input is ignored until the model binds`() = runTest(dispatcher) {
    val vm = viewModel()
    assertFalse(vm.acceptsPins)
    vm.onPinPressed(0)
    vm.onTensionToggled()
    assertEquals(-1, vm.uiState.value.selectedPin)
    assertFalse(vm.uiState.value.tension)
    assertNull(vm.readout)
    assertNull(vm.telemetry())
  }

  @Test
  fun `binding the model publishes its geometry`() = runTest(dispatcher) {
    val vm = bound(viewModel())
    assertEquals(testGeometry, vm.uiState.value.geometry)
    assertTrue(vm.acceptsPins)
  }

  @Test
  fun `controls update the ui state`() = runTest(dispatcher) {
    val vm = bound(viewModel())
    vm.onTensionToggled()
    vm.onGhostToggled()
    vm.onExplodedToggled()
    vm.onPresetSelected(CameraPreset.ShearSide)
    vm.onPresetSelected(CameraPreset.ShearSide)
    val state = vm.uiState.value
    assertTrue(state.tension)
    assertTrue(state.ghost)
    assertEquals(1f, state.explode, 0f)
    assertEquals(CameraPreset.ShearSide, state.preset)
    assertEquals(2, state.presetResetKey)
  }

  @Test
  fun `explode is clamped and survives until the model binds`() = runTest(dispatcher) {
    val vm = viewModel()
    vm.onExplodeChanged(3f)
    assertEquals(1f, vm.uiState.value.explode, 0f)
    bound(vm)
    play()
    assertEquals(1f, vm.uiState.value.explode, 0f)
  }

  @Test
  fun `picking in binding order opens the lock and emits haptic events`() = runTest(dispatcher) {
    val vm = bound(viewModel())
    val events = mutableListOf<LockEvent>()
    backgroundScope.launch { vm.events.collect { events += it } }
    vm.onTensionToggled()
    for (pin in bindingOrder) {
      vm.onPinTapped(pin)
      play()
    }
    assertTrue(vm.uiState.value.opened)
    assertFalse(vm.acceptsPins)
    assertEquals(4, vm.uiState.value.setPulse)
    assertEquals(3, events.count { it is LockEvent.PinSet })
    assertEquals(LockEvent.Opened, events.last())
  }

  @Test
  fun `dragging a pin reports its lift in telemetry`() = runTest(dispatcher) {
    val vm = bound(viewModel())
    vm.onPinPressed(1)
    play(0.5f)
    vm.onPinDragged(1, 0.2f)
    play(0.1f)
    val telemetry = vm.telemetry()!!
    assertEquals("KeyPin_2", telemetry.selectedPinNode)
    assertTrue(telemetry.selectedLiftMm > 0f)
    assertEquals(1, vm.uiState.value.selectedPin)
  }

  @Test
  fun `the motion tick advances while a pin moves`() = runTest(dispatcher) {
    val vm = bound(viewModel())
    vm.onPinPressed(0)
    play(0.5f)
    val before = vm.motionTick.value
    vm.onPinDragged(0, 0.3f)
    play(0.1f)
    assertTrue(vm.motionTick.value > before)
  }

  @Test
  fun `a rebind after rotation keeps the lock as it was`() = runTest(dispatcher) {
    val vm = viewModel()
    val firstScene = startScene(vm)
    vm.onTensionToggled()
    vm.onPinTapped(bindingOrder.first())
    play()
    assertEquals(1, vm.uiState.value.setCount)

    // The scene is torn down and rebuilt: a new rig reports the same geometry and restarts the loop.
    firstScene.cancel()
    startScene(vm)
    play()
    assertEquals(1, vm.uiState.value.setCount)
    assertTrue(vm.uiState.value.tension)
    assertEquals(PinState.Set, vm.readout!!.state(bindingOrder.first()))
  }

  @Test
  fun `reset relocks an open lock`() = runTest(dispatcher) {
    val vm = bound(viewModel())
    vm.onTensionToggled()
    for (pin in bindingOrder) {
      vm.onPinTapped(pin)
      play()
    }
    vm.onReset()
    play(5f)
    val state = vm.uiState.value
    assertFalse(state.opened)
    assertFalse(state.tension)
    assertEquals(0, state.setCount)
  }

  private companion object {
    const val FRAME_MILLIS = 16L
  }
}
