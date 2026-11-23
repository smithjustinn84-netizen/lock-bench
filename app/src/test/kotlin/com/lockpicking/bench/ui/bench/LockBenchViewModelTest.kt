package com.lockpicking.bench.ui.bench

import com.lockpicking.bench.data.LockAssetRepository
import com.lockpicking.bench.data.glb.GlbInfo
import com.lockpicking.bench.domain.bench.Keyway
import com.lockpicking.bench.domain.lock.LockEvent
import com.lockpicking.bench.domain.lock.LockMechanics
import com.lockpicking.bench.domain.lock.PinState
import com.lockpicking.bench.domain.lock.TorqueBand
import com.lockpicking.bench.domain.motion.FrameClock
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

  private val keyway = Keyway(testGeometry)

  /** The thumb puts the pick under [pin] and lifts it to [fraction] of that pin's travel. */
  private fun LockBenchViewModel.dragPin(pin: Int, fraction: Float) {
    val maxLift = testGeometry.pinSpecs[pin].liftToShear + LockMechanics.MAX_OVERTRAVEL
    val range = testGeometry.pinSpecs.maxOf { it.liftToShear } + LockMechanics.MAX_OVERTRAVEL
    onPickMoved(keyway.depthOf(pin), fraction * maxLift / range)
  }

  private fun TestScope.bound(vm: LockBenchViewModel): LockBenchViewModel = vm.also { startScene(it) }

  /** Lets the frame loop play out [seconds] of virtual time. */
  private fun TestScope.play(seconds: Float = 2f) = advanceTimeBy((seconds * 1000).toLong())

  /** Pulls the wrench lightly and taps whichever pin is binding until the lock opens. */
  private fun TestScope.pickOpen(vm: LockBenchViewModel) {
    vm.onWrenchPulled(EASED)
    play(0.5f)
    repeat(2 * testGeometry.pinCount) {
      if (vm.uiState.value.opened) return@repeat
      vm.onPinTapped(vm.uiState.value.bindingPin)
      play()
    }
  }

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
    vm.onPickMoved(0.5f, 0.5f)
    vm.onPickTapped(0.5f)
    vm.onWrenchPulled(0.5f)
    assertEquals(-1, vm.uiState.value.selectedPin)
    assertFalse(vm.uiState.value.tension)
    assertNull(vm.readout)
    assertNull(vm.pose)
    assertNull(vm.telemetry())
  }

  @Test
  fun `binding the model publishes its geometry`() = runTest(dispatcher) {
    val vm = bound(viewModel())
    assertEquals(testGeometry, vm.uiState.value.geometry)
  }

  @Test
  fun `controls update the ui state`() = runTest(dispatcher) {
    val vm = bound(viewModel())
    vm.onHintsToggled()
    vm.onGhostToggled()
    vm.onExplodedToggled()
    vm.onPresetSelected(CameraPreset.ShearSide)
    vm.onPresetSelected(CameraPreset.ShearSide)
    val state = vm.uiState.value
    assertFalse(state.hints)
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
  fun `pulling the wrench publishes the band and the live torque`() = runTest(dispatcher) {
    val vm = bound(viewModel())
    vm.onWrenchPulled(0.5f)
    play(0.5f)
    assertEquals(TorqueBand.Firm, vm.uiState.value.band)
    assertTrue(vm.uiState.value.tension)
    assertEquals(0.5f, vm.pose!!.torque, 0f)
    assertEquals(0.5f, vm.telemetry()!!.torque, 0f)

    vm.onWrenchReleased()
    play(0.5f)
    assertEquals(TorqueBand.Slack, vm.uiState.value.band)
    assertEquals(0f, vm.pose!!.torque, 0f)
  }

  @Test
  fun `the motion tick advances while the torque changes`() = runTest(dispatcher) {
    val vm = bound(viewModel())
    play(0.5f)
    val before = vm.motionTick.value
    vm.onWrenchPulled(0.5f)
    play(0.1f)
    assertTrue(vm.motionTick.value > before)
  }

  @Test
  fun `picking the binding pin each time opens the lock and emits its events`() = runTest(dispatcher) {
    val vm = bound(viewModel())
    val events = mutableListOf<LockEvent>()
    val scrapes = mutableListOf<LockEvent>()
    backgroundScope.launch { vm.events.collect { events += it.event } }
    backgroundScope.launch { vm.textures.collect { scrapes += it.event } }
    pickOpen(vm)
    assertTrue(vm.uiState.value.opened)
    assertEquals(4, vm.uiState.value.setPulse)
    assertEquals(3, events.count { it is LockEvent.PinSet })
    assertEquals(2, events.count { it is LockEvent.FalseSet })
    assertEquals(LockEvent.Opened, events.last())
    assertTrue("textures are kept off the event stream", events.none { it is LockEvent.Grit || it is LockEvent.SpringTick })
    assertTrue(scrapes.any { it is LockEvent.Grit })
    assertTrue(scrapes.all { it is LockEvent.Grit || it is LockEvent.SpringTick || it == LockEvent.WardScrape })
  }

  @Test
  fun `events carry the torque they happened under`() = runTest(dispatcher) {
    val vm = bound(viewModel())
    val sets = mutableListOf<Float>()
    backgroundScope.launch { vm.events.collect { if (it.event is LockEvent.PinSet) sets += it.torque } }
    vm.onWrenchPulled(0.5f)
    play(0.5f)
    vm.onPinTapped(vm.uiState.value.bindingPin)
    play()
    assertEquals(listOf(0.5f), sets)
  }

  @Test
  fun `a blocked spool and a false set reach the ui state`() = runTest(dispatcher) {
    val vm = bound(viewModel())
    vm.onWrenchPulled(0.5f)
    play(0.5f)
    repeat(2) {
      vm.onPinTapped(vm.uiState.value.bindingPin)
      play()
    }
    // Pin 2 comes off its flange and the plug drops into the spools.
    vm.dragPin(1, 0f)
    play(0.5f)
    vm.dragPin(1, 0.15f)
    play(0.5f)
    vm.onPickRested()
    play(0.5f)
    assertEquals(2, vm.uiState.value.falseSetPin)
    assertEquals(BenchStatus.FalseSet(pinNumber = 3), vm.uiState.value.status())

    vm.dragPin(2, 0f)
    play(0.5f)
    vm.dragPin(2, 0.5f)
    play(0.5f)
    assertEquals(2, vm.uiState.value.blockedPin)
    assertEquals(BenchStatus.EaseOff(pinNumber = 3), vm.uiState.value.status())
  }

  @Test
  fun `dragging a pin reports its lift in telemetry`() = runTest(dispatcher) {
    val vm = bound(viewModel())
    vm.dragPin(1, 0f)
    play(0.5f)
    vm.dragPin(1, 0.2f)
    play(0.1f)
    val telemetry = vm.telemetry()!!
    assertEquals("KeyPin_2", telemetry.selectedPinNode)
    assertTrue(telemetry.selectedLiftMm > 0f)
    assertEquals(1, vm.uiState.value.selectedPin)
  }

  @Test
  fun `the motion tick advances while a pin moves`() = runTest(dispatcher) {
    val vm = bound(viewModel())
    vm.dragPin(0, 0f)
    play(0.5f)
    val before = vm.motionTick.value
    vm.dragPin(0, 0.3f)
    play(0.1f)
    assertTrue(vm.motionTick.value > before)
  }

  @Test
  fun `a rebind after rotation keeps the lock as it was`() = runTest(dispatcher) {
    val vm = viewModel()
    val firstScene = startScene(vm)
    vm.onWrenchPulled(0.5f)
    play(0.5f)
    val first = vm.uiState.value.bindingPin
    vm.onPinTapped(first)
    play()
    assertEquals(1, vm.uiState.value.setCount)

    // The scene is torn down and rebuilt: a new rig reports the same geometry and restarts the loop.
    firstScene.cancel()
    startScene(vm)
    play()
    assertEquals(1, vm.uiState.value.setCount)
    assertTrue(vm.uiState.value.tension)
    assertEquals(PinState.Set, vm.readout!!.state(first))
  }

  @Test
  fun `reset relocks an open lock`() = runTest(dispatcher) {
    val vm = bound(viewModel())
    pickOpen(vm)
    assertTrue(vm.uiState.value.opened)
    vm.onReset()
    play(5f)
    val state = vm.uiState.value
    assertFalse(state.opened)
    assertFalse(state.tension)
    assertEquals(0, state.setCount)
  }

  private companion object {
    const val FRAME_MILLIS = 16L

    /** Light enough that a spool turns the plug back without help. */
    const val EASED = 0.15f
  }
}
