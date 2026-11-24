package com.lockpicking.bench.ui.bench

import android.view.ViewConfiguration
import androidx.activity.compose.ReportDrawnWhen
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.IntState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onLayoutRectChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toRect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lockpicking.bench.R
import com.lockpicking.bench.domain.bench.PickTool
import com.lockpicking.bench.domain.game.GameTier
import com.lockpicking.bench.domain.lock.LockMechanics
import com.lockpicking.bench.domain.lock.LockReadout
import com.lockpicking.bench.domain.model.LockModel
import com.lockpicking.bench.ui.bench.feedback.LockFeedback
import com.lockpicking.bench.ui.bench.feedback.LockHaptics
import com.lockpicking.bench.ui.bench.feedback.LockSoundEngine
import com.lockpicking.bench.ui.bench.feedback.cueFor
import com.lockpicking.bench.ui.bench.input.BenchTouchHandler
import com.lockpicking.bench.ui.bench.input.BenchTouchRouter
import com.lockpicking.bench.ui.bench.input.TouchRect
import com.lockpicking.bench.ui.bench.juice.BenchJuice
import com.lockpicking.bench.ui.bench.juice.JuiceOverlay
import com.lockpicking.bench.ui.bench.juice.JuiceRules
import com.lockpicking.bench.ui.bench.juice.juiceShake
import com.lockpicking.bench.ui.bench.juice.rememberBenchJuice
import com.lockpicking.bench.ui.bench.scene.BenchScene
import com.lockpicking.bench.ui.components.BenchButton
import com.lockpicking.bench.ui.components.ControlBar
import com.lockpicking.bench.ui.components.GameStatusBlock
import com.lockpicking.bench.ui.components.GuidePanel
import com.lockpicking.bench.ui.components.PickPad
import com.lockpicking.bench.ui.components.PickPadInset
import com.lockpicking.bench.ui.components.ResultsCard
import com.lockpicking.bench.ui.components.StatusBlock
import com.lockpicking.bench.ui.components.TensionLever
import com.lockpicking.bench.ui.components.Vignette
import com.lockpicking.bench.ui.components.WrenchThrowLength
import com.lockpicking.bench.ui.components.arrive
import com.lockpicking.bench.ui.theme.BenchColors
import com.lockpicking.bench.ui.theme.BenchType
import org.koin.compose.viewmodel.koinViewModel

/** Thumb travel that lifts the pick's tip through its whole range. */
private val PickLiftRange = 150.dp

/** Room the status block, the stats pill and the drawer button take at the top of the screen. */
private val HudTopBand = 68.dp

/** The two thumb zones. The lock is framed in what they leave between them. */
private val TensionZoneWidth = 132.dp
private val PickPadWidth = 264.dp
private val HudMargin = 12.dp
private val DrawerWidth = 400.dp
private val ResultsWidth = 280.dp
private val GuideWidth = 560.dp

/** The parts of the HUD the touch router has to know the whereabouts of. */
enum class HudPart {
  /** The 3D view itself: every other rectangle is measured from its corner. */
  Scene,

  /** The tension zone. A thumb that lands here is a hand on the wrench. */
  Wrench,

  /** The pick pad. A thumb that lands here is a hand on the pick. */
  Pick,

  /** The drawer, which takes its own touches even where it lies over a thumb zone. */
  Controls,

  /** The results card after a blind round, which takes its own touches. */
  Results,

  /** The guide, which takes its own touches. */
  Guide,

  /** The hook and rake switch in the pick pad's header: buttons, not part of the trackpad around them. */
  Tool,
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
    router.pickBounds = local(HudPart.Pick) ?: TouchRect.Empty
    router.blocked = listOfNotNull(local(HudPart.Controls), local(HudPart.Results), local(HudPart.Guide), local(HudPart.Tool))
  }
}

/**
 * Hears where this node lands, in window pixels, after every layout that moves it. Not
 * debounced: the touch router has to know where a thumb zone is the moment the hands swap.
 */
fun Modifier.reportBounds(onBounds: (Rect) -> Unit): Modifier =
  onLayoutRectChanged(throttleMillis = 0, debounceMillis = 0) { onBounds(it.boundsInWindow.toRect()) }

/** Wires the bench screen to its [LockBenchViewModel]. */
@Composable
fun LockBenchRoute(viewModel: LockBenchViewModel = koinViewModel()) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  // Fully drawn once the splash lets go: the saved lock is known and the bench is on screen.
  ReportDrawnWhen { state.restored }
  // Snapshot state, read only in the draw phase of the canvases that follow it.
  val motionTick = viewModel.motionTick

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
  // The HUD reacts to the same events, from this one collector: the channel has one reader.
  val juice = rememberBenchJuice()
  val currentState by rememberUpdatedState(state)
  LaunchedEffect(viewModel, feel, juice) {
    viewModel.events.collect {
      feel(it)
      juice.onEvent(it.event, currentState.juiceRules())
    }
  }
  LaunchedEffect(viewModel, feel) { viewModel.textures.collect { feel(it) } }

  val density = LocalDensity.current
  val touch = remember(viewModel) {
    BenchTouchHandler(BenchTouchRouter(viewModel, viewModel, ViewConfiguration.get(context).scaledTouchSlop.toFloat()))
  }
  val layout = remember(touch) { TouchLayout(touch.router) }
  SideEffect(touch, density) {
    with(density) {
      touch.router.dragRangePx = PickLiftRange.toPx()
      // The thumb and the pick drawn on the pad move together.
      touch.router.depthRangePx = (PickPadWidth - PickPadInset * 2).toPx()
      touch.router.wrenchThrowPx = WrenchThrowLength.toPx()
    }
  }
  // Behind another app there is no hand on the wrench, and the round's clock stops.
  LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) {
    touch.router.onCancel()
    viewModel.onPaused()
  }
  LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onResumed() }
  // Picking blind, the camera stays on the keyway: orbiting would show the cut side.
  val blind = state.blind
  SideEffect(touch, blind) { touch.router.cameraLocked = blind }
  val meter = remember { FrameMeter() }

  LockBenchScreen(
    state = state,
    motionTick = motionTick,
    pins = viewModel.readout,
    torque = { viewModel.pose?.torque ?: 0f },
    plugDegrees = { viewModel.pose?.plugDegrees ?: 0f },
    pickZ = { viewModel.pose?.pickZ ?: Float.NaN },
    pickY = { viewModel.pose?.pickY ?: 0f },
    meter = meter,
    telemetry = viewModel::telemetry,
    onExplodeChange = viewModel::onExplodeChanged,
    onExplodedToggle = viewModel::onExplodedToggled,
    onHintsToggle = viewModel::onHintsToggled,
    onGhostToggle = viewModel::onGhostToggled,
    onReset = {
      touch.router.letGo()
      viewModel.onReset()
    },
    onSwapHands = {
      // The zones are about to change places under whatever thumbs are down.
      touch.router.letGo()
      viewModel.onHandsSwapped()
    },
    onPreset = viewModel::onPresetSelected,
    onTool = { tool ->
      // The pick comes out of the lock; the thumb on the wrench stays where it is.
      touch.router.letGoOfPick()
      viewModel.onToolSelected(tool)
    },
    onLock = { lock ->
      // The lock under the thumbs is about to be taken off the bench.
      touch.router.letGo()
      viewModel.onLockSelected(lock)
    },
    onPlay = { tier ->
      touch.router.letGo()
      viewModel.onGameStarted(tier)
    },
    onQuit = {
      touch.router.letGo()
      viewModel.onGameQuit()
    },
    // The guide covers the thumb zones: nothing stays held under it.
    onGuide = touch.router::letGo,
    onNextLock = {
      touch.router.letGo()
      viewModel.onNextLock()
    },
    gameElapsedMillis = viewModel::gameElapsedMillis,
    juice = juice,
    onPlaced = layout::place,
  ) { contentPadding ->
    // Until the saved lock is known the splash screen is still up: load nothing.
    if (state.restored) BenchScene(
      host = viewModel,
      lock = state.lock,
      modelInfo = state.modelInfo,
      preset = state.preset,
      presetResetKey = state.presetResetKey,
      contentPadding = contentPadding,
      meter = meter,
      touch = touch,
      modifier = Modifier.fillMaxSize().reportBounds { layout.place(HudPart.Scene, it) },
    )
  }
}

/**
 * The bench layout, for a phone held in landscape in two hands: the 3D [scene] full-bleed,
 * the tension zone under one thumb and the pick pad under the other, and everything else out
 * of their way in a drawer. Stateless; [scene] gets the padding that frames the lock between
 * the two zones, and [onPlaced] hears where the parts of the HUD that matter to touch have
 * been laid out, in window pixels.
 */
@Composable
fun LockBenchScreen(
  state: BenchUiState,
  motionTick: IntState,
  pins: LockReadout?,
  torque: () -> Float,
  plugDegrees: () -> Float,
  pickZ: () -> Float,
  pickY: () -> Float,
  meter: FrameMeter,
  telemetry: () -> BenchTelemetry?,
  onExplodeChange: (Float) -> Unit,
  onExplodedToggle: () -> Unit,
  onHintsToggle: () -> Unit,
  onGhostToggle: () -> Unit,
  onReset: () -> Unit,
  onSwapHands: () -> Unit,
  onPreset: (CameraPreset) -> Unit,
  onLock: (LockModel) -> Unit,
  onTool: (PickTool) -> Unit,
  onPlay: (GameTier) -> Unit,
  onQuit: () -> Unit,
  onNextLock: () -> Unit,
  onGuide: () -> Unit,
  gameElapsedMillis: () -> Long,
  modifier: Modifier = Modifier,
  juice: BenchJuice = rememberBenchJuice(),
  onPlaced: (HudPart, Rect) -> Unit = { _, _ -> },
  scene: @Composable (contentPadding: PaddingValues) -> Unit,
) {
  var drawerOpen by rememberSaveable { mutableStateOf(false) }
  var guideOpen by rememberSaveable { mutableStateOf(false) }
  val direction = LocalLayoutDirection.current
  val insets = WindowInsets.safeDrawing.asPaddingValues()
  // Tension is the left hand's job unless the hands are swapped.
  val wrenchOnLeft = !state.swapHands
  val leftZone = if (wrenchOnLeft) TensionZoneWidth else PickPadWidth
  val rightZone = if (wrenchOnLeft) PickPadWidth else TensionZoneWidth
  val sceneInsets = PaddingValues(
    start = insets.calculateStartPadding(direction) + HudMargin + leftZone,
    top = insets.calculateTopPadding() + HudTopBand,
    end = insets.calculateEndPadding(direction) + HudMargin + rightZone,
    bottom = insets.calculateBottomPadding(),
  )

  Box(modifier.fillMaxSize().background(BenchColors.Ink)) {
    scene(sceneInsets)
    Vignette()
    Box(
      Modifier
        .fillMaxSize()
        .padding(insets)
        .padding(horizontal = HudMargin, vertical = 8.dp),
    ) {
      Row(Modifier.fillMaxWidth().align(Alignment.TopStart).juiceShake(juice), verticalAlignment = Alignment.Top) {
        val game = state.game
        if (game != null) {
          GameStatusBlock(game, state.lock, state.setCount, state.pinCount, gameElapsedMillis, Modifier.weight(1f).padding(end = 12.dp))
        } else {
          StatusBlock(state.status(), state.swapHands, Modifier.weight(1f).padding(end = 12.dp))
          // Pin lift in millimetres and the plug's angle: not for a blind round.
          LiveStatsPill(state.modelInfo, meter, telemetry)
        }
        // The primer is for the open bench: a blind round's clock would run on under it.
        Row(Modifier.padding(start = 8.dp).width(84.dp)) {
          BenchButton(stringResource(R.string.control_guide), selected = guideOpen, enabled = state.game == null) {
            if (!guideOpen) {
              drawerOpen = false
              onGuide()
            }
            guideOpen = !guideOpen
          }
        }
        Row(Modifier.padding(start = 8.dp).width(84.dp)) {
          BenchButton(stringResource(R.string.control_menu), selected = drawerOpen) {
            drawerOpen = !drawerOpen
            if (drawerOpen) guideOpen = false
          }
        }
      }
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
        val zone = Modifier.padding(top = HudTopBand).fillMaxHeight()
        TensionLever(
          band = state.band,
          motionTick = motionTick,
          torque = torque,
          plugDegrees = plugDegrees,
          showZones = state.game?.tier?.showWrenchZones ?: true,
          seizeAt = state.game?.tier?.seizeAt ?: LockMechanics.T_HEAVY,
          showSetTicks = state.game?.tier?.showSetCount ?: true,
          modifier = zone
            .align(if (wrenchOnLeft) Alignment.BottomStart else Alignment.BottomEnd)
            .width(TensionZoneWidth)
            .reportBounds { onPlaced(HudPart.Wrench, it) }
            // Inside the reported bounds: the shake never moves the zone under a thumb.
            .juiceShake(juice),
        )
        PickPad(
          geometry = geometry,
          pins = pins,
          motionTick = motionTick,
          setPulse = state.setPulse,
          setCount = state.setCount,
          opened = state.opened,
          // Which pin is binding is exactly what the hints give away.
          bindingPin = if (state.hints) state.bindingPin else -1,
          selectedPin = state.selectedPin,
          pickZ = pickZ,
          pickY = pickY,
          blind = state.blind,
          showSetCount = state.game?.tier?.showSetCount ?: true,
          tool = state.tool,
          onTool = onTool.takeIf { state.hasRake },
          onToolSwitchPlaced = { onPlaced(HudPart.Tool, it) },
          juice = juice,
          modifier = zone
            .align(if (wrenchOnLeft) Alignment.BottomEnd else Alignment.BottomStart)
            .width(PickPadWidth)
            .reportBounds { onPlaced(HudPart.Pick, it) }
            .juiceShake(juice),
        )
      }
      // With no lock on the bench there is no pad, and no switch on it to keep touches off.
      DisposableEffect(geometry != null) { onDispose { onPlaced(HudPart.Tool, Rect.Zero) } }
      val result = state.game?.result
      if (result != null) {
        ResultsCard(
          result = result,
          onNext = onNextLock,
          onQuit = onQuit,
          modifier = Modifier
            .align(Alignment.BottomCenter)
            .width(ResultsWidth)
            .reportBounds { onPlaced(HudPart.Results, it) },
        )
      }
      DisposableEffect(result != null) { onDispose { onPlaced(HudPart.Results, Rect.Zero) } }
      if (drawerOpen) {
        ControlBar(
          explode = state.explode,
          exploded = state.exploded,
          hints = state.hints,
          ghost = state.ghost,
          swapHands = state.swapHands,
          preset = state.preset,
          lock = state.lock,
          gameTier = state.game?.tier,
          blind = state.blind,
          onExplodeChange = onExplodeChange,
          onExplodedToggle = onExplodedToggle,
          onHintsToggle = onHintsToggle,
          onGhostToggle = onGhostToggle,
          onReset = onReset,
          onSwapHands = onSwapHands,
          onPreset = onPreset,
          onLock = onLock,
          onPlay = {
            drawerOpen = false
            onPlay(it)
          },
          onQuit = {
            drawerOpen = false
            onQuit()
          },
          modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(top = 56.dp)
            .width(DrawerWidth)
            .reportBounds { onPlaced(HudPart.Controls, it) }
            .arrive(),
        )
      }
      // A closed drawer is not in the way of anything.
      DisposableEffect(drawerOpen) { onDispose { onPlaced(HudPart.Controls, Rect.Zero) } }
      if (guideOpen) {
        GuidePanel(
          onClose = { guideOpen = false },
          modifier = Modifier
            .align(Alignment.TopCenter)
            .padding(top = 56.dp)
            .width(GuideWidth)
            .fillMaxHeight()
            .reportBounds { onPlaced(HudPart.Guide, it) }
            .arrive(),
        )
      }
      DisposableEffect(guideOpen) { onDispose { onPlaced(HudPart.Guide, Rect.Zero) } }
    }
    // Over everything, and takes no touches: flashes, confetti, the banner on release.
    JuiceOverlay(juice, sceneInsets, showBanner = state.game == null)
  }
}

/** What the HUD may celebrate: only what the bench already shows. */
private fun BenchUiState.juiceRules() = JuiceRules(
  showSets = game?.tier?.showSetCount ?: true,
  showPins = !blind,
)
