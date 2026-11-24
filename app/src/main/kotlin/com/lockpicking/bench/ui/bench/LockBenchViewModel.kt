package com.lockpicking.bench.ui.bench

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lockpicking.bench.data.BenchPrefs
import com.lockpicking.bench.data.GameRecords
import com.lockpicking.bench.data.LockAssetRepository
import com.lockpicking.bench.domain.bench.BenchSimulation
import com.lockpicking.bench.domain.bench.BenchSnapshot
import com.lockpicking.bench.domain.game.GameClock
import com.lockpicking.bench.domain.game.GameRound
import com.lockpicking.bench.domain.game.GameTier
import com.lockpicking.bench.domain.lock.LockEvent
import com.lockpicking.bench.domain.lock.LockMechanics
import com.lockpicking.bench.domain.lock.LockReadout
import com.lockpicking.bench.domain.model.LockGeometry
import com.lockpicking.bench.domain.model.LockModel
import com.lockpicking.bench.domain.motion.FrameClock
import com.lockpicking.bench.domain.motion.RigPose
import com.lockpicking.bench.ui.bench.feedback.LockFeedback
import com.lockpicking.bench.ui.bench.input.PickInput
import com.lockpicking.bench.ui.bench.input.WrenchInput
import com.lockpicking.bench.ui.bench.scene.BenchSceneHost
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Owns the bench's state for the life of the screen, rotation included.
 *
 * The 3D scene lives and dies with the composition, so it never reaches in here. It reports
 * the lock's [LockGeometry] once the model binds ([onModelBound]), then drives [runFrameLoop]
 * with the display's frame clock and poses itself from what each frame hands back.
 *
 * It also swaps locks. A lock is a model, so choosing one ([onLockSelected]) empties the bench
 * until the scene has loaded and bound the new one.
 *
 * It also runs the blind game: a [GameRound] deals a lock with a shuffled binding order,
 * scores what the lock reports, and times the round on [clock].
 */
class LockBenchViewModel(
  private val assets: LockAssetRepository,
  private val records: GameRecords,
  private val clock: GameClock,
  private val prefs: BenchPrefs,
) : ViewModel(), PickInput, WrenchInput, BenchSceneHost {

  private val _uiState = MutableStateFlow(BenchUiState())
  val uiState: StateFlow<BenchUiState> = _uiState.asStateFlow()

  private val _motionTick = MutableStateFlow(0)

  /** Changes on every frame a pin moved. Read it only where pins are drawn, in the draw phase. */
  val motionTick: StateFlow<Int> = _motionTick.asStateFlow()

  private val _events = Channel<LockFeedback>(Channel.BUFFERED)

  /** One-shot lock events with the torque they happened under, for haptics and sound. */
  val events: Flow<LockFeedback> = _events.receiveAsFlow()

  private val _textures = MutableSharedFlow<LockFeedback>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)

  /**
   * What the pick's tip drags over: a binding pin scraping up, a spring giving, a land sliding
   * past. These come in a stream while the pick moves. Kept apart
   * from [events] and allowed to drop, so a burst of texture can never hold up or push out a set.
   */
  val textures: SharedFlow<LockFeedback> = _textures.asSharedFlow()

  private val wake = Channel<Unit>(Channel.CONFLATED)
  private var simulation: BenchSimulation? = null
  private var lastSnapshot: BenchSnapshot? = null
  private var round: GameRound? = null
  private val random = Random.Default

  /** Whether the hints were on before a game turned them off, to give them back after. */
  private var sandboxHints = true

  /** Live pin travel, or null until the model has bound. */
  val readout: LockReadout? get() = simulation?.readout

  /** The live pose, torque and plug angle included, or null until the model has bound. */
  val pose: RigPose? get() = simulation?.pose

  private var infoLoad: Job? = null

  init {
    // The bench opens as it was left: lock, camera and hints. Nothing loads until that is known,
    // so the trainer never flashes up first.
    viewModelScope.launch {
      val saved = prefs.load()
      sandboxHints = saved.hints
      _uiState.update { it.copy(lock = saved.lock, preset = saved.preset, hints = saved.hints, restored = true) }
      loadModelInfo(saved.lock)
    }
  }

  private fun loadModelInfo(lock: LockModel) {
    infoLoad?.cancel()
    infoLoad = viewModelScope.launch {
      try {
        val info = assets.loadModelInfo(lock)
        // A slow read must not land on a bench that has moved on to another lock.
        _uiState.update { if (it.lock == lock) it.copy(modelInfo = info) else it }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        onModelFailed(e.message ?: e.toString())
      }
    }
  }

  /**
   * Puts [lock] on the bench. The lock that was there goes at once, simulation and all; the new
   * one arrives when the scene has loaded its model and calls [onModelBound]. Not while a blind
   * round is being picked: that lock has to be opened or given up first.
   */
  fun onLockSelected(lock: LockModel) {
    if (!_uiState.value.restored || picking() || lock == _uiState.value.lock) return
    val hints = if (_uiState.value.game != null) sandboxHints else _uiState.value.hints
    round = null
    simulation = null
    lastSnapshot = null
    _motionTick.value = 0
    _uiState.update {
      BenchUiState(
        lock = lock,
        hints = hints,
        explode = it.explode,
        ghost = it.ghost,
        swapHands = it.swapHands,
        preset = if (it.game != null) CameraPreset.ShearSide else it.preset,
        presetResetKey = it.presetResetKey + 1,
        restored = true,
      )
    }
    viewModelScope.launch { prefs.saveLock(lock) }
    loadModelInfo(lock)
  }

  // ── Scene lifecycle ─────────────────────────────────────────────────────────────────────────

  /**
   * The 3D model is bound and these are its dimensions. Called again on every new scene (after
   * a rotation, say); the running simulation is kept so the lock comes back exactly as it was.
   */
  override fun onModelBound(geometry: LockGeometry) {
    val current = simulation
    if (current == null || current.geometry != geometry) {
      val state = _uiState.value
      simulation = BenchSimulation(geometry, ::emit).apply {
        setExplode(state.explode)
        setGhost(state.ghost)
        round?.let { deal(it.bindingOrder, it.tier.seizeAt) }
      }
      lastSnapshot = null
    }
    _uiState.update { it.copy(geometry = geometry, loadError = null) }
    publish()
    wake()
  }

  override fun onModelFailed(message: String) {
    _uiState.update { it.copy(loadError = message) }
  }

  /**
   * Steps the bench on [clock] and hands every frame's pose to [onFrame]. Sleeps while nothing
   * moves, so an idle bench asks the renderer for nothing; any intent wakes it. Runs until
   * cancelled. Call it from the composition, whose frame clock this needs.
   */
  override suspend fun runFrameLoop(clock: FrameClock, onFrame: (RigPose, LockReadout) -> Unit) {
    val sim = simulation ?: return
    // A new scene: pose it at once, then run a frame in case it was mid-motion.
    onFrame(sim.pose, sim.readout)
    wake()
    while (true) {
      wake.receive()
      var last = 0L
      do {
        val now = clock.awaitFrameNanos()
        val dt = if (last == 0L) FIRST_FRAME_SECONDS else ((now - last) / 1e9f).coerceIn(0f, MAX_FRAME_SECONDS)
        last = now
        val moving = sim.advance(dt)
        onFrame(sim.pose, sim.readout)
        publish()
      } while (moving)
    }
  }

  /** The numbers behind the stats readout, or null before the model binds. */
  fun telemetry(): BenchTelemetry? {
    val sim = simulation ?: return null
    val pin = _uiState.value.selectedPin
    return BenchTelemetry(
      selectedPinNode = if (pin >= 0) sim.geometry.pinNodeNames[pin] else null,
      selectedLiftMm = if (pin >= 0) sim.readout.keyLift(pin) * MM_PER_UNIT else 0f,
      plugDegrees = sim.pose.plugDegrees,
      torque = sim.pose.torque,
    )
  }

  // ── Pick input ──────────────────────────────────────────────────────────────────────────────

  override val pickDepth: Float get() = simulation?.pickDepthNow ?: 0f

  override fun onPickMoved(depth: Float, lift: Float) = act { movePick(depth, lift * liftRange) }

  override fun onPickRested() = act { letGo() }

  override fun onPickTapped(across: Float) {
    val count = simulation?.geometry?.pinCount ?: return
    onPinTapped((across * count).toInt().coerceIn(0, count - 1))
  }

  /** The beginner's shortcut: the pick goes to [pin], lifts it exactly to the shear line, and lets go. */
  fun onPinTapped(pin: Int) {
    // Blind, there is no tapping a pin up to the shear line: that would give the lock away.
    if (_uiState.value.game != null) return
    act { nudge(pin) }
  }

  // ── Wrench input ────────────────────────────────────────────────────────────────────────────

  override fun onWrenchPulled(amount: Float) {
    val sim = simulation ?: return
    // The clock starts with the first pull on a lock that is ready to be picked.
    if (!sim.isOpen) round?.start(clock.nowMillis())
    act { pullWrench(amount) }
  }

  override fun onWrenchReleased() = act { releaseWrench() }

  // ── Controls ────────────────────────────────────────────────────────────────────────────────

  fun onHintsToggled() {
    if (picking()) return
    val hints = !_uiState.value.hints
    _uiState.update { it.copy(hints = hints) }
    // After a round the hints go back to the bench's own when the game ends: only those are kept.
    if (_uiState.value.game == null) viewModelScope.launch { prefs.saveHints(hints) }
  }

  fun onHandsSwapped() = _uiState.update { it.copy(swapHands = !it.swapHands) }

  fun onReset() = act { reset() }

  fun onExplodeChanged(amount: Float) {
    if (picking()) return
    val clamped = amount.coerceIn(0f, 1f)
    _uiState.update { it.copy(explode = clamped) }
    act { setExplode(clamped) }
  }

  fun onExplodedToggled() = onExplodeChanged(if (_uiState.value.exploded) 0f else 1f)

  fun onGhostToggled() {
    if (picking()) return
    val ghost = !_uiState.value.ghost
    _uiState.update { it.copy(ghost = ghost) }
    act { setGhost(ghost) }
  }

  fun onPresetSelected(preset: CameraPreset) {
    if (picking()) return
    _uiState.update { it.copy(preset = preset, presetResetKey = it.presetResetKey + 1) }
    // Looking around the reveal after a round is not a choice for the open bench.
    if (_uiState.value.game == null) viewModelScope.launch { prefs.savePreset(preset) }
  }

  /**
   * A tap on the shackle. On an open lock it closes it, the same as RESET; after a game round
   * it deals the next lock. Returns true if the tap did something.
   */
  override fun onShackleTapped(): Boolean {
    val state = _uiState.value
    if (!state.opened) return false
    if (state.game != null) onNextLock() else onReset()
    return true
  }

  // ── Game ────────────────────────────────────────────────────────────────────────────────────

  /** Deal the first blind lock of [tier]. Needs the model bound. */
  fun onGameStarted(tier: GameTier) {
    if (simulation == null) return
    if (_uiState.value.game == null) sandboxHints = _uiState.value.hints
    round = null
    dealRound(tier, lockNumber = 1)
  }

  /** Relock and deal another lock of the same tier. */
  fun onNextLock() {
    val game = _uiState.value.game ?: return
    dealRound(game.tier, game.lockNumber + 1)
  }

  /** Give up the round and go back to the open bench with its usual lock. */
  fun onGameQuit() {
    if (_uiState.value.game == null) return
    round = null
    simulation?.let { it.deal(LockMechanics.defaultBindingOrder(it.geometry.pinCount)) }
    _uiState.update {
      it.copy(game = null, hints = sandboxHints, preset = CameraPreset.ShearSide, presetResetKey = it.presetResetKey + 1)
    }
    publish()
    wake()
  }

  /** The app went behind another: stop the clock. */
  fun onPaused() {
    round?.pause(clock.nowMillis())
  }

  fun onResumed() {
    round?.resume(clock.nowMillis())
  }

  /** Time on the round's clock, for a timer that polls it. Zero outside a game. */
  fun gameElapsedMillis(): Long = round?.elapsed(clock.nowMillis()) ?: 0L

  /** True while a blind round is being picked: the bench's see-through aids are off limits. */
  private fun picking(): Boolean = _uiState.value.game?.let { it.result == null } == true

  private fun dealRound(tier: GameTier, lockNumber: Int) {
    val sim = simulation ?: return
    val lock = _uiState.value.lock
    val par = (tier.parSeconds * lock.parFactor).roundToInt()
    val next = GameRound(tier, sim.geometry.pinCount, random, previousOrder = round?.bindingOrder, parSeconds = par)
    round = next
    sim.setExplode(0f)
    sim.setGhost(false)
    sim.deal(next.bindingOrder, tier.seizeAt)
    _uiState.update {
      it.copy(
        game = GameUiState(tier = tier, lockNumber = lockNumber, bestMillis = null),
        hints = false,
        ghost = false,
        explode = 0f,
        // The keyway from the solid side: the cut half of the housing is never in view.
        preset = CameraPreset.Blind,
        presetResetKey = it.presetResetKey + 1,
      )
    }
    publish()
    wake()
    // The best time comes off disk a moment later; it only matters once the round is scored.
    viewModelScope.launch {
      val best = records.bestMillis(lock, tier) ?: return@launch
      if (round !== next) return@launch
      _uiState.update { state -> state.copy(game = state.game?.takeIf { it.result == null }?.copy(bestMillis = best) ?: state.game) }
    }
  }

  private fun scoreEvent(event: LockEvent) {
    val current = round ?: return
    val before = current.mistakes
    if (current.onEvent(event, clock.nowMillis())) {
      val lock = _uiState.value.lock
      val previousBest = _uiState.value.game?.bestMillis
      val result = current.result(previousBest)
      if (result.newBest) viewModelScope.launch { records.saveBest(lock, current.tier, result.timeMillis) }
      val best = if (result.newBest) result.timeMillis else previousBest
      _uiState.update {
        it.copy(
          game = it.game?.copy(result = result, mistakes = result.mistakes, bestMillis = best),
          // The reveal: round to the cut side, to see what you felt.
          preset = CameraPreset.ShearSide,
          presetResetKey = it.presetResetKey + 1,
        )
      }
    } else if (current.mistakes != before) {
      _uiState.update { it.copy(game = it.game?.copy(mistakes = current.mistakes)) }
    }
  }

  // ── Internals ───────────────────────────────────────────────────────────────────────────────

  /** Runs [action] on the simulation, if there is one yet, then publishes and wakes the loop. */
  private inline fun act(action: BenchSimulation.() -> Unit) {
    val sim = simulation ?: return
    sim.action()
    publish()
    wake()
  }

  private fun emit(event: LockEvent) {
    scoreEvent(event)
    val feedback = LockFeedback(event, simulation?.pose?.torque ?: 0f)
    val texture = event is LockEvent.Grit || event is LockEvent.SpringTick || event === LockEvent.WardScrape
    if (texture) _textures.tryEmit(feedback) else _events.trySend(feedback)
  }

  private fun wake() {
    wake.trySend(Unit)
  }

  private fun publish() {
    val sim = simulation ?: return
    _motionTick.value = sim.motionTick
    val snapshot = sim.snapshot()
    if (snapshot == lastSnapshot) return
    lastSnapshot = snapshot
    _uiState.update {
      it.copy(
        band = snapshot.band,
        opened = snapshot.opened,
        setCount = snapshot.setCount,
        bindingPin = snapshot.bindingPin,
        oversetPin = snapshot.oversetPin,
        falseSetPin = snapshot.falseSetPin,
        blockedPin = snapshot.blockedPin,
        selectedPin = snapshot.selectedPin,
        setPulse = snapshot.setPulse,
      )
    }
  }

  private companion object {
    const val FIRST_FRAME_SECONDS = 1f / 60f
    const val MAX_FRAME_SECONDS = 1f / 20f

    /** 1 model unit = 100 mm. */
    const val MM_PER_UNIT = 100f
  }
}
