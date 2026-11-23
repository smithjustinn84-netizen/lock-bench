package com.lockpicking.bench.ui.bench

import android.view.ViewConfiguration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lockpicking.bench.R
import com.lockpicking.bench.domain.lock.LockReadout
import com.lockpicking.bench.ui.bench.feedback.LockFeedback
import com.lockpicking.bench.ui.bench.feedback.LockHaptics
import com.lockpicking.bench.ui.bench.feedback.LockSoundEngine
import com.lockpicking.bench.ui.bench.feedback.cueFor
import com.lockpicking.bench.ui.bench.input.BenchTouchHandler
import com.lockpicking.bench.ui.bench.input.BenchTouchRouter
import com.lockpicking.bench.ui.bench.input.TouchRect
import com.lockpicking.bench.ui.bench.scene.BenchScene
import com.lockpicking.bench.ui.components.ControlBar
import com.lockpicking.bench.ui.components.ShearLineIndicator
import com.lockpicking.bench.ui.components.StatusBlock
import com.lockpicking.bench.ui.components.TensionLever
import com.lockpicking.bench.ui.components.Vignette
import com.lockpicking.bench.ui.components.WrenchThrowLength
import com.lockpicking.bench.ui.theme.BenchColors
import com.lockpicking.bench.ui.theme.BenchType
import org.koin.compose.viewmodel.koinViewModel

/** Finger travel that lifts a pin through its whole range. */
private val PinDragRange = 150.dp

/** Room the status block and stats pill take at the top of the screen. */
private val HudTopBand = 64.dp

/** The parts of the HUD the touch router has to know the whereabouts of. */
enum class HudPart {
  /** The 3D view itself: every other rectangle is measured from its corner. */
  Scene,

  /** The tension lever. A finger that lands here is a hand on the wrench. */
  Wrench,

  /** Panels that take their own touches, so that a pin behind one is not picked through it. */
  PinReadout,
  Controls,
}

/**
 * Tells the [router] where the HUD is. The HUD reports window pixels; the router's touches
 * are in the 3D view's own pixels.
 */
private class TouchLayout(private val router: BenchTouchRouter) {
  private val placed = arrayOfNulls<Rect>(HudPart.entries.size)

  fun place(part: HudPart, bounds: Rect) {
    if (placed[part.ordinal] == bounds) return
    placed[part.ordinal] = bounds
    val origin = placed[HudPart.Scene.ordinal]?.topLeft ?: Offset.Zero
    fun local(part: HudPart): TouchRect? = placed[part.ordinal]?.let {
      TouchRect(it.left - origin.x, it.top - origin.y, it.right - origin.x, it.bottom - origin.y)
    }
    router.wrenchBounds = local(HudPart.Wrench) ?: TouchRect.Empty
    router.blocked = listOfNotNull(local(HudPart.PinReadout), local(HudPart.Controls))
  }
}

/** Wires the bench screen to its [LockBenchViewModel]. */
@Composable
fun LockBenchRoute(viewModel: LockBenchViewModel = koinViewModel()) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  // Kept as a State and read only in the draw phase of the canvases that follow it.
  val motionTick = viewModel.motionTick.collectAsStateWithLifecycle()

  val context = LocalContext.current
  val haptics = remember(context) { LockHaptics(context.applicationContext) }
  val sounds = remember(context) { LockSoundEngine(context) }
  DisposableEffect(sounds) { onDispose { sounds.release() } }
  val feel = remember(haptics, sounds) {
    { feedback: LockFeedback ->
      val cue = cueFor(feedback.event, feedback.torque)
      cue.haptic?.let { haptics.play(it, cue.strength) }
      cue.sound?.let { sounds.play(it, cue.strength) }
    }
  }
  LaunchedEffect(viewModel, feel) { viewModel.events.collect { feel(it) } }
  LaunchedEffect(viewModel, feel) { viewModel.textures.collect { feel(it) } }

  val density = LocalDensity.current
  val touch = remember(viewModel) {
    BenchTouchHandler(BenchTouchRouter(viewModel, viewModel, ViewConfiguration.get(context).scaledTouchSlop.toFloat()))
  }
  val layout = remember(touch) { TouchLayout(touch.router) }
  with(density) {
    touch.router.dragRangePx = PinDragRange.toPx()
    touch.router.wrenchThrowPx = WrenchThrowLength.toPx()
  }
  // Behind another app there is no hand on the wrench.
  LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { touch.router.onCancel() }
  val meter = remember { FrameMeter() }

  LockBenchScreen(
    state = state,
    motionTick = motionTick,
    pins = viewModel.readout,
    torque = { viewModel.pose?.torque ?: 0f },
    plugDegrees = { viewModel.pose?.plugDegrees ?: 0f },
    meter = meter,
    telemetry = viewModel::telemetry,
    onNudge = viewModel::onPinTapped,
    onExplodeChange = viewModel::onExplodeChanged,
    onExplodedToggle = viewModel::onExplodedToggled,
    onHintsToggle = viewModel::onHintsToggled,
    onGhostToggle = viewModel::onGhostToggled,
    onReset = {
      touch.router.letGo()
      viewModel.onReset()
    },
    onPreset = viewModel::onPresetSelected,
    onPlaced = layout::place,
  ) { contentPadding ->
    BenchScene(
      host = viewModel,
      modelInfo = state.modelInfo,
      preset = state.preset,
      presetResetKey = state.presetResetKey,
      contentPadding = contentPadding,
      meter = meter,
      touch = touch,
      modifier = Modifier.fillMaxSize().onGloballyPositioned { layout.place(HudPart.Scene, it.boundsInWindow()) },
    )
  }
}

/**
 * The bench layout: the 3D [scene] full-bleed, with the HUD over it. Stateless; [scene] gets
 * the padding that keeps the lock clear of the HUD, and [onPlaced] hears where the parts of
 * the HUD that matter to touch have been laid out, in window pixels.
 */
@Composable
fun LockBenchScreen(
  state: BenchUiState,
  motionTick: State<Int>,
  pins: LockReadout?,
  torque: () -> Float,
  plugDegrees: () -> Float,
  meter: FrameMeter,
  telemetry: () -> BenchTelemetry?,
  onNudge: (Int) -> Unit,
  onExplodeChange: (Float) -> Unit,
  onExplodedToggle: () -> Unit,
  onHintsToggle: () -> Unit,
  onGhostToggle: () -> Unit,
  onReset: () -> Unit,
  onPreset: (CameraPreset) -> Unit,
  modifier: Modifier = Modifier,
  onPlaced: (HudPart, Rect) -> Unit = { _, _ -> },
  scene: @Composable (contentPadding: PaddingValues) -> Unit,
) {
  val density = LocalDensity.current
  var barHeightPx by remember { mutableIntStateOf(0) }
  val insets = WindowInsets.safeDrawing.asPaddingValues()
  val sceneInsets = PaddingValues(
    top = insets.calculateTopPadding() + HudTopBand,
    bottom = with(density) { barHeightPx.toDp() },
  )

  Box(modifier.fillMaxSize().background(BenchColors.Ink)) {
    scene(sceneInsets)
    Vignette()
    Column(
      Modifier
        .fillMaxSize()
        .padding(insets)
        .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
      Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        StatusBlock(state.status(), Modifier.weight(1f).padding(end = 12.dp))
        LiveStatsPill(state.modelInfo, meter, telemetry)
      }
      Box(Modifier.weight(1f).fillMaxWidth()) {
        state.loadError?.let {
          Text(
            stringResource(R.string.model_load_failed, it),
            style = BenchType.Readout,
            color = BenchColors.Fault,
            modifier = Modifier.align(Alignment.Center),
          )
        }
        val geometry = state.geometry
        if (geometry != null && pins != null) {
          ShearLineIndicator(
            geometry = geometry,
            pins = pins,
            motionTick = motionTick,
            setPulse = state.setPulse,
            setCount = state.setCount,
            opened = state.opened,
            // Which pin is binding is exactly what the hints give away.
            bindingPin = if (state.hints) state.bindingPin else -1,
            selectedPin = state.selectedPin,
            onNudge = onNudge,
            modifier = Modifier
              .align(Alignment.BottomStart)
              .width(168.dp)
              .padding(bottom = 8.dp)
              .onGloballyPositioned { onPlaced(HudPart.PinReadout, it.boundsInWindow()) },
          )
          TensionLever(
            band = state.band,
            motionTick = motionTick,
            torque = torque,
            plugDegrees = plugDegrees,
            modifier = Modifier
              .align(Alignment.BottomEnd)
              .padding(bottom = 8.dp)
              .onGloballyPositioned { onPlaced(HudPart.Wrench, it.boundsInWindow()) },
          )
        }
      }
      ControlBar(
        explode = state.explode,
        exploded = state.exploded,
        hints = state.hints,
        ghost = state.ghost,
        preset = state.preset,
        onExplodeChange = onExplodeChange,
        onExplodedToggle = onExplodedToggle,
        onHintsToggle = onHintsToggle,
        onGhostToggle = onGhostToggle,
        onReset = onReset,
        onPreset = onPreset,
        modifier = Modifier
          .fillMaxWidth()
          .onSizeChanged { barHeightPx = it.height }
          .onGloballyPositioned { onPlaced(HudPart.Controls, it.boundsInWindow()) },
      )
    }
  }
}
