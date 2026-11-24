package com.lockpicking.bench.ui.bench

import com.lockpicking.bench.data.BenchPrefs
import com.lockpicking.bench.data.GameRecords
import com.lockpicking.bench.data.LockAssetRepository
import com.lockpicking.bench.data.glb.GlbInfo
import com.lockpicking.bench.domain.bench.Keyway
import com.lockpicking.bench.domain.game.GameClock
import com.lockpicking.bench.domain.game.GameTier
import com.lockpicking.bench.domain.lock.LockEvent
import com.lockpicking.bench.domain.lock.LockMechanics
import com.lockpicking.bench.domain.lock.PinState
import com.lockpicking.bench.domain.lock.TorqueBand
import com.lockpicking.bench.domain.model.LockModel
import com.lockpicking.bench.domain.motion.FrameClock
import com.lockpicking.bench.testing.american1100Geometry
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
import org.junit.Assert.assertNotNull
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
    val asked = mutableListOf<LockModel>()

    override suspend fun loadModelInfo(lock: LockModel): GlbInfo {
      asked += lock
      return result()
    }
  }

  @Before
  fun setUp() = Dispatchers.setMain(dispatcher)

  @After
  fun tearDown() = Dispatchers.resetMain()

  private class FakeRecords : GameRecords {
    val best = mutableMapOf<Pair<LockModel, GameTier>, Long>()
    override fun bestMillis(lock: LockModel, tier: GameTier): Long? = best[lock to tier]
    override fun saveBest(lock: LockModel, tier: GameTier, millis: Long) {
      best[lock to tier] = millis
    }
  }

  private val records = FakeRecords()

  private class FakePrefs(var lock: LockModel? = null) : BenchPrefs {
    override fun lastLock(): LockModel? = lock
    override fun saveLastLock(lock: LockModel) {
      this.lock = lock
    }
  }

  private val prefs = FakePrefs()

  /** The round's clock runs on the test scheduler's virtual time. */
  private val clock = GameClock { dispatcher.scheduler.currentTime }

  private var repository = FakeRepository { modelInfo }

  private fun viewModel(result: () -> GlbInfo = { modelInfo }): LockBenchViewModel {
    repository = FakeRepository(result)
    return LockBenchViewModel(repository, records, clock, prefs)
  }

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

  /**
   * Picks the lock open by hand, the way a player has to when blind: light tension, then the
   * binding pin raised a little at a time until it sets.
   */
  private fun TestScope.pickOpenByHand(vm: LockBenchViewModel) {
    vm.onWrenchPulled(EASED)
    play(0.5f)
    repeat(4 * testGeometry.pinCount) {
      val state = vm.uiState.value
      if (state.opened) return
      val pin = state.bindingPin
      if (pin < 0) return@repeat
      vm.dragPin(pin, 0f)
      play(0.2f)
      var fraction = 0f
      while (fraction < 1f && vm.readout!!.state(pin) != PinState.Set && !vm.uiState.value.opened) {
        fraction += 0.02f
        vm.dragPin(pin, fraction)
        play(0.1f)
      }
      vm.onPickRested()
      play(0.3f)
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

  @Test
  fun `a blind round hides the bench's aids and ignores pin taps`() = runTest(dispatcher) {
    val vm = bound(viewModel())
    vm.onGhostToggled()
    vm.onGameStarted(GameTier.Journeyman)
    val state = vm.uiState.value
    assertTrue(state.blind)
    assertFalse(state.hints)
    assertFalse(state.ghost)
    assertEquals(CameraPreset.Blind, state.preset)

    // None of the see-through controls work while picking blind.
    vm.onHintsToggled()
    vm.onGhostToggled()
    vm.onPresetSelected(CameraPreset.ShearSide)
    vm.onWrenchPulled(0.5f)
    play(0.5f)
    vm.onPinTapped(vm.uiState.value.bindingPin)
    vm.onPickTapped(0.5f)
    play()
    assertFalse(vm.uiState.value.hints)
    assertFalse(vm.uiState.value.ghost)
    assertEquals(CameraPreset.Blind, vm.uiState.value.preset)
    assertEquals(0, vm.uiState.value.setCount)
  }

  @Test
  fun `the clock starts on the first pull and stops while paused`() = runTest(dispatcher) {
    val vm = bound(viewModel())
    vm.onGameStarted(GameTier.Apprentice)
    play(1f)
    assertEquals(0L, vm.gameElapsedMillis())
    vm.onWrenchPulled(0.3f)
    play(1f)
    val running = vm.gameElapsedMillis()
    assertTrue(running in 900L..1100L)
    vm.onPaused()
    play(5f)
    assertEquals(running, vm.gameElapsedMillis())
    vm.onResumed()
    play(1f)
    assertTrue(vm.gameElapsedMillis() > running)
  }

  @Test
  fun `opening a blind lock scores the round, reveals the pins and keeps the best time`() = runTest(dispatcher) {
    val vm = bound(viewModel())
    vm.onGameStarted(GameTier.Apprentice)
    pickOpenByHand(vm)
    val state = vm.uiState.value
    assertTrue(state.opened)
    val result = state.game?.result
    assertNotNull(result)
    result!!
    assertFalse("the reveal shows the pins", state.blind)
    assertEquals(CameraPreset.ShearSide, state.preset)
    assertTrue(result.newBest)
    assertEquals(result.timeMillis, records.best[LockModel.Trainer to GameTier.Apprentice])
    assertTrue(result.stars in 1..3)

    // The clock is stopped once the shackle releases.
    play(3f)
    assertEquals(result.timeMillis, vm.gameElapsedMillis())
  }

  @Test
  fun `tapping the shackle of an open lock relocks it`() = runTest(dispatcher) {
    val vm = bound(viewModel())
    assertFalse("a closed lock ignores the tap", vm.onShackleTapped())
    pickOpen(vm)
    assertTrue(vm.onShackleTapped())
    play(5f)
    assertFalse(vm.uiState.value.opened)
    assertEquals(0, vm.uiState.value.setCount)
  }

  @Test
  fun `tapping the shackle after a blind round deals the next lock`() = runTest(dispatcher) {
    val vm = bound(viewModel())
    vm.onGameStarted(GameTier.Apprentice)
    pickOpenByHand(vm)
    assertTrue(vm.uiState.value.opened)
    assertTrue(vm.onShackleTapped())
    play(5f)
    val state = vm.uiState.value
    assertFalse(state.opened)
    assertTrue(state.blind)
    assertEquals(2, state.game!!.lockNumber)
    assertEquals(CameraPreset.Blind, state.preset)
    assertNull(state.game.result)
  }

  @Test
  fun `giving up goes back to the open bench and its usual lock`() = runTest(dispatcher) {
    val vm = bound(viewModel())
    vm.onGameStarted(GameTier.Master)
    vm.onGameQuit()
    play()
    val state = vm.uiState.value
    assertNull(state.game)
    assertTrue(state.hints)
    vm.onWrenchPulled(EASED)
    play(0.5f)
    // The usual lock: pin 3 is first in its order, but a spool at rest has its flange clear of
    // the shear line, so pin 1 is the one that binds.
    assertEquals(0, vm.uiState.value.bindingPin)
  }

  // ── Locks ───────────────────────────────────────────────────────────────────────────────────

  @Test
  fun `choosing another lock empties the bench until its model binds`() = runTest(dispatcher) {
    val vm = bound(viewModel())
    advanceUntilIdle()
    vm.onWrenchPulled(EASED)
    play(0.5f)
    assertTrue(vm.uiState.value.tension)

    vm.onLockSelected(LockModel.American1100)
    var state = vm.uiState.value
    assertEquals(LockModel.American1100, state.lock)
    // Nothing of the old lock is left to draw or to pick.
    assertNull(state.modelInfo)
    assertNull(state.geometry)
    assertNull(vm.readout)
    assertFalse(state.tension)

    advanceUntilIdle()
    assertEquals(listOf(LockModel.Trainer, LockModel.American1100), repository.asked)
    assertSame(modelInfo, vm.uiState.value.modelInfo)

    vm.onModelBound(american1100Geometry)
    state = vm.uiState.value
    assertEquals(5, state.pinCount)
    assertEquals(5, vm.readout!!.pinCount)
  }

  @Test
  fun `the chosen lock is remembered for the next launch`() = runTest(dispatcher) {
    val vm = bound(viewModel())
    vm.onLockSelected(LockModel.Master3)
    assertEquals(LockModel.Master3, prefs.lock)

    val next = viewModel()
    assertEquals(LockModel.Master3, next.uiState.value.lock)
    advanceUntilIdle()
    assertEquals(listOf(LockModel.Master3), repository.asked)
  }

  @Test
  fun `a blind round that is refused a lock change leaves the saved lock alone`() = runTest(dispatcher) {
    val vm = bound(viewModel())
    vm.onGameStarted(GameTier.Apprentice)
    vm.onLockSelected(LockModel.Master3)
    assertNull(prefs.lock)
  }

  @Test
  fun `the bench settings survive a change of lock`() = runTest(dispatcher) {
    val vm = bound(viewModel())
    vm.onHintsToggled()
    vm.onHandsSwapped()
    vm.onGhostToggled()
    vm.onLockSelected(LockModel.Master3)
    val state = vm.uiState.value
    assertFalse(state.hints)
    assertTrue(state.swapHands)
    assertTrue(state.ghost)
  }

  @Test
  fun `a blind lock cannot be swapped for another while it is being picked`() = runTest(dispatcher) {
    val vm = bound(viewModel())
    vm.onGameStarted(GameTier.Apprentice)
    vm.onLockSelected(LockModel.Master3)
    assertEquals(LockModel.Trainer, vm.uiState.value.lock)
    assertNotNull(vm.uiState.value.game)
  }

  @Test
  fun `changing lock after a round ends the game and gives the hints back`() = runTest(dispatcher) {
    val vm = bound(viewModel())
    vm.onGameStarted(GameTier.Apprentice)
    pickOpenByHand(vm)
    assertNotNull(vm.uiState.value.game!!.result)
    vm.onLockSelected(LockModel.Master3)
    val state = vm.uiState.value
    assertEquals(LockModel.Master3, state.lock)
    assertNull(state.game)
    assertTrue(state.hints)
  }

  @Test
  fun `each lock keeps its own best times and stretches the par`() = runTest(dispatcher) {
    val vm = bound(viewModel())
    vm.onLockSelected(LockModel.American1100)
    vm.onModelBound(american1100Geometry)
    backgroundScope.launch { vm.runFrameLoop(frameClock()) { _, _ -> } }
    records.best[LockModel.Trainer to GameTier.Apprentice] = 1_000L
    vm.onGameStarted(GameTier.Apprentice)
    // The trainer's record is not this lock's.
    assertNull(vm.uiState.value.game!!.bestMillis)
  }

  private companion object {
    const val FRAME_MILLIS = 16L

    /** Light enough that a spool turns the plug back without help. */
    const val EASED = 0.15f
  }
}
