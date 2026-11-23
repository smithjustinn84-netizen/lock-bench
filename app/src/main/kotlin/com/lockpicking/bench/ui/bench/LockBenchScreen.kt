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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lockpicking.bench.R
import com.lockpicking.bench.domain.lock.LockReadout
import com.lockpicking.bench.ui.bench.input.PinTouchHandler
import com.lockpicking.bench.ui.bench.scene.BenchScene
import com.lockpicking.bench.ui.components.ControlBar
import com.lockpicking.bench.ui.components.ShearLineIndicator
import com.lockpicking.bench.ui.components.StatusBlock
import com.lockpicking.bench.ui.components.Vignette
import com.lockpicking.bench.ui.theme.BenchColors
import com.lockpicking.bench.ui.theme.BenchType
import org.koin.compose.viewmodel.koinViewModel

/** Finger travel that lifts a pin through its whole range. */
private val PinDragRange = 150.dp

/** Room the status block and stats pill take at the top of the screen. */
private val HudTopBand = 64.dp

/** Wires the bench screen to its [LockBenchViewModel]. */
@Composable
fun LockBenchRoute(viewModel: LockBenchViewModel = koinViewModel()) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  // Kept as a State and read only in the shear-line canvas's draw phase.
  val motionTick = viewModel.motionTick.collectAsStateWithLifecycle()

  val hostView = LocalView.current
  LaunchedEffect(viewModel, hostView) {
    viewModel.events.collect { hostView.performLockHaptic(it) }
  }

  val context = LocalContext.current
  val density = LocalDensity.current
  val touch = remember(viewModel) {
    PinTouchHandler(viewModel, ViewConfiguration.get(context).scaledTouchSlop.toFloat())
  }
  touch.dragRangePx = with(density) { PinDragRange.toPx() }
  val meter = remember { FrameMeter() }

  LockBenchScreen(
    state = state,
    motionTick = motionTick,
    pins = viewModel.readout,
    meter = meter,
    telemetry = viewModel::telemetry,
    onNudge = viewModel::onPinTapped,
    onExplodeChange = viewModel::onExplodeChanged,
    onExplodedToggle = viewModel::onExplodedToggled,
    onTensionToggle = viewModel::onTensionToggled,
    onGhostToggle = viewModel::onGhostToggled,
    onReset = {
      touch.cancel()
      viewModel.onReset()
    },
    onPreset = viewModel::onPresetSelected,
  ) { contentPadding ->
    BenchScene(
      host = viewModel,
      modelInfo = state.modelInfo,
      preset = state.preset,
      presetResetKey = state.presetResetKey,
      contentPadding = contentPadding,
      meter = meter,
      touch = touch,
      modifier = Modifier.fillMaxSize(),
    )
  }
}

/**
 * The bench layout: the 3D [scene] full-bleed, with the HUD over it. Stateless; [scene] gets
 * the padding that keeps the lock clear of the HUD.
 */
@Composable
fun LockBenchScreen(
  state: BenchUiState,
  motionTick: State<Int>,
  pins: LockReadout?,
  meter: FrameMeter,
  telemetry: () -> BenchTelemetry?,
  onNudge: (Int) -> Unit,
  onExplodeChange: (Float) -> Unit,
  onExplodedToggle: () -> Unit,
  onTensionToggle: () -> Unit,
  onGhostToggle: () -> Unit,
  onReset: () -> Unit,
  onPreset: (CameraPreset) -> Unit,
  modifier: Modifier = Modifier,
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
            bindingPin = state.bindingPin,
            selectedPin = state.selectedPin,
            onNudge = onNudge,
            modifier = Modifier.align(Alignment.BottomStart).width(168.dp).padding(bottom = 8.dp),
          )
        }
      }
      ControlBar(
        explode = state.explode,
        exploded = state.exploded,
        tension = state.tension,
        tensionEnabled = !state.opened,
        ghost = state.ghost,
        preset = state.preset,
        onExplodeChange = onExplodeChange,
        onExplodedToggle = onExplodedToggle,
        onTensionToggle = onTensionToggle,
        onGhostToggle = onGhostToggle,
        onReset = onReset,
        onPreset = onPreset,
        modifier = Modifier.fillMaxWidth().onSizeChanged { barHeightPx = it.height },
      )
    }
  }
}
