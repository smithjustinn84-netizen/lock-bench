package com.lockpicking.bench.ui.bench

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lockpicking.bench.data.LockAssetRepository
import com.lockpicking.bench.domain.bench.BenchSimulation
import com.lockpicking.bench.domain.bench.BenchSnapshot
import com.lockpicking.bench.domain.lock.LockEvent
import com.lockpicking.bench.domain.lock.LockReadout
import com.lockpicking.bench.domain.model.LockGeometry
import com.lockpicking.bench.domain.motion.FrameClock
import com.lockpicking.bench.domain.motion.RigPose
import com.lockpicking.bench.ui.bench.feedback.LockFeedback
import com.lockpicking.bench.ui.bench.input.PinInput
import com.lockpicking.bench.ui.bench.input.WrenchInput
import com.lockpicking.bench.ui.bench.scene.BenchSceneHost
import kotlinx.coroutines.CancellationException
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

/**
 * Owns the bench's state for the life of the screen, rotation included.
 *
 * The 3D scene lives and dies with the composition, so it never reaches in here. It reports
 * the lock's [LockGeometry] once the model binds ([onModelBound]), then drives [runFrameLoop]
 * with the display's frame clock and poses itself from what each frame hands back.
 */
class LockBenchViewModel(
  private val assets: LockAssetRepository,
) : ViewModel(), PinInput, WrenchInput, BenchSceneHost {

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
   * The scrape of a binding pin, which comes in a stream while it is being lifted. Kept apart
   * from [events] and allowed to drop, so a burst of texture can never hold up or push out a set.
   */
  val textures: SharedFlow<LockFeedback> = _textures.asSharedFlow()

  private val wake = Channel<Unit>(Channel.CONFLATED)
  private var simulation: BenchSimulation? = null
  private var lastSnapshot: BenchSnapshot? = null

  /** Live pin travel, or null until the model has bound. */
  val readout: LockReadout? get() = simulation?.readout

  /** The live pose, torque and plug angle included, or null until the model has bound. */
  val pose: RigPose? get() = simulation?.pose

  init {
    viewModelScope.launch {
      try {
        val info = assets.loadModelInfo()
        _uiState.update { it.copy(modelInfo = info) }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        onModelFailed(e.message ?: e.toString())
      }
    }
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

  // ── Pin input ───────────────────────────────────────────────────────────────────────────────

  override val acceptsPins: Boolean get() = simulation?.isOpen == false

  override fun onPinPressed(pin: Int) = act { hold(pin, 0f) }

  override fun onPinDragged(pin: Int, fraction: Float) = act { hold(pin, fraction * maxLift(pin)) }

  override fun onPinReleased() = act { letGo() }

  override fun onPinTapped(pin: Int) = act { nudge(pin) }

  // ── Wrench input ────────────────────────────────────────────────────────────────────────────

  override fun onWrenchPulled(amount: Float) = act { pullWrench(amount) }

  override fun onWrenchReleased() = act { releaseWrench() }

  // ── Controls ────────────────────────────────────────────────────────────────────────────────

  fun onHintsToggled() = _uiState.update { it.copy(hints = !it.hints) }

  fun onReset() = act { reset() }

  fun onExplodeChanged(amount: Float) {
    val clamped = amount.coerceIn(0f, 1f)
    _uiState.update { it.copy(explode = clamped) }
    act { setExplode(clamped) }
  }

  fun onExplodedToggled() = onExplodeChanged(if (_uiState.value.exploded) 0f else 1f)

  fun onGhostToggled() {
    val ghost = !_uiState.value.ghost
    _uiState.update { it.copy(ghost = ghost) }
    act { setGhost(ghost) }
  }

  fun onPresetSelected(preset: CameraPreset) {
    _uiState.update { it.copy(preset = preset, presetResetKey = it.presetResetKey + 1) }
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
    val feedback = LockFeedback(event, simulation?.pose?.torque ?: 0f)
    if (event is LockEvent.Grit) _textures.tryEmit(feedback) else _events.trySend(feedback)
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
