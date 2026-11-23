package com.lockpicking.bench.ui

import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import io.github.sceneview.ExperimentalSceneViewApi
import io.github.sceneview.SceneView
import io.github.sceneview.collision.CollisionSystem
import io.github.sceneview.environment.rememberHDREnvironment
import io.github.sceneview.math.Direction
import io.github.sceneview.math.Position
import io.github.sceneview.node.ModelNode
import io.github.sceneview.rememberCameraManipulator
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberCollisionSystem
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberEnvironment
import io.github.sceneview.rememberEnvironmentLoader
import io.github.sceneview.rememberMainLightNode
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberRenderInvalidator
import io.github.sceneview.rememberRenderer
import io.github.sceneview.rememberView
import com.lockpicking.bench.lock.BenchUi
import com.lockpicking.bench.lock.GlbInfo
import com.lockpicking.bench.lock.LockEvent
import com.lockpicking.bench.lock.LockRig
import com.lockpicking.bench.lock.LockSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs

private const val MODEL = "models/cutaway_padlock.glb"
private const val STUDIO = "environments/studio.hdr"
private const val TAG = "LockBench"

/** Eye and orbit target in model units. Selecting one glides the camera there. */
enum class CameraPreset(val label: String, val eye: Position, val target: Position) {
  Isometric("ISO", Position(1.32f, 0.70f, 0.86f), Position(0f, 0.17f, 0f)),
  KeywayFront("KEYWAY", Position(0f, 0.12f, 1.70f), Position(0f, 0.17f, 0f)),
  ShearSide("SHEAR", Position(1.30f, 0.11f, 0f), Position(0f, 0.11f, 0f)),
}

/** Presented-frame counter fed from SceneView's onFrame. Plain fields: read on a slow tick. */
private class FrameMeter {
  var frames = 0
  var lastFrameAt = 0L
}

@OptIn(ExperimentalSceneViewApi::class)
@Composable
fun LockBenchScreen() {
  val context = LocalContext.current
  val hostView = LocalView.current
  val density = LocalDensity.current
  val scope = rememberCoroutineScope()

  val engine = rememberEngine()
  val modelLoader = rememberModelLoader(engine)
  val environmentLoader = rememberEnvironmentLoader(engine)
  val view = rememberView(engine)
  val renderer = rememberRenderer(engine)
  val collisionSystem = rememberCollisionSystem(view)
  val invalidator = rememberRenderInvalidator()

  // No skybox: the room is lit by the studio HDR but drawn as a flat dark backdrop.
  remember(renderer) {
    renderer.clearOptions = renderer.clearOptions.apply {
      clear = true
      clearColor = doubleArrayOf(0.014, 0.016, 0.020, 1.0)
    }
  }

  val glb = remember { GlbInfo.read(context, MODEL) }
  val ui = remember { BenchUi() }
  var preset by remember { mutableStateOf(CameraPreset.Isometric) }
  var presetTaps by remember { mutableIntStateOf(0) }
  var session by remember { mutableStateOf<LockSession?>(null) }
  var loadError by remember { mutableStateOf<String?>(null) }
  var barHeightPx by remember { mutableIntStateOf(0) }
  val meter = remember { FrameMeter() }

  val cameraNode = rememberCameraNode(engine) {
    position = CameraPreset.Isometric.eye
    lookAt(CameraPreset.Isometric.target)
    near = 0.02f
    far = 40f
  }
  // The key light: high, from the cutaway side, so the pins throw shadows down their chambers.
  // Configured once, not in rememberMainLightNode's `apply`: that block re-runs on every
  // recomposition and each light write asks SceneView for a frame, which would keep an
  // otherwise parked scene rendering.
  val keyLight = rememberMainLightNode(engine)
  remember(keyLight) {
    keyLight.intensity = 70_000f
    keyLight.lightDirection = Direction(-0.62f, -1.0f, -0.48f)
    keyLight.updateShadowOptions {
      mapSize = 2048
      // The whole bench is under two units across: keep the shadow map on it.
      shadowFar = 6f
      shadowNearHint = 0.2f
      shadowFarHint = 6f
    }
  }
  val studio = rememberHDREnvironment(environmentLoader, STUDIO, createSkybox = false)
  val neutral = rememberEnvironment(environmentLoader)

  val manipulator = rememberCameraManipulator(
    orbitHomePosition = preset.eye,
    targetPosition = preset.target,
    key = presetTaps,
  )

  val touch = remember(collisionSystem) { PinTouch(collisionSystem, hostView, scope) }
  touch.session = session
  touch.dragRangePx = with(density) { 150.dp.toPx() }

  session?.let { live ->
    LaunchedEffect(live) { live.run { invalidator.requestRender() } }
    // Slider and toggles write BenchUi directly; the sleeping frame loop needs telling.
    LaunchedEffect(live, ui.explode, ui.ghost) { live.wake() }
  }

  val insets = WindowInsets.safeDrawing.asPaddingValues()
  val barHeight = with(density) { barHeightPx.toDp() }

  Box(Modifier.fillMaxSize().background(Bench.Ink)) {
    SceneView(
      modifier = Modifier.fillMaxSize(),
      engine = engine,
      modelLoader = modelLoader,
      environmentLoader = environmentLoader,
      view = view,
      renderer = renderer,
      renderInvalidator = invalidator,
      // The model is authored around the plug axis and the rig poses it in those coordinates.
      autoCenterContent = false,
      // Frame the lock in what the HUD leaves visible, without resizing the surface.
      contentPadding = PaddingValues(top = insets.calculateTopPadding() + 64.dp, bottom = barHeight),
      environment = studio ?: neutral,
      mainLightNode = keyLight,
      fillLightNode = null,
      cameraNode = cameraNode,
      collisionSystem = collisionSystem,
      cameraManipulator = manipulator,
      onTouchEvent = { event, _ -> touch.handle(event) },
      onFrame = {
        meter.frames++
        meter.lastFrameAt = SystemClock.uptimeMillis()
      },
    ) {
      rememberModelInstance(modelLoader, MODEL)?.let { instance ->
        val holder = remember(instance) { arrayOfNulls<ModelNode>(1) }
        ModelNode(modelInstance = instance, autoAnimate = false, apply = { holder[0] = this })
        LaunchedEffect(instance) {
          val node = holder[0] ?: return@LaunchedEffect
          runCatching { LockSession(LockRig(node, glb), ui) { event -> hostView.click(event) } }
            .onSuccess { session = it }
            .onFailure {
              Log.e(TAG, "could not bind the lock model", it)
              loadError = it.message
            }
        }
      }
    }

    // View tweaks go after the SceneView call: it applies its RenderQuality preset from an
    // effect of its own, and later effects win. Bloom is off because polished metal under a
    // softbox throws the occasional out-of-range highlight pixel, and bloom's mip chain turns
    // that one pixel into a flickering block; without it the highlights stay crisp and the
    // frame is cheaper.
    LaunchedEffect(view) {
      view.bloomOptions = view.bloomOptions.apply { enabled = false }
      invalidator.requestRender()
    }

    Vignette()

    Column(
      Modifier
        .fillMaxSize()
        .padding(insets)
        .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
      Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        StatusBlock(ui, LockRig.PIN_COUNT, Modifier.weight(1f).padding(end = 12.dp))
        LiveStatsPill(glb, meter, session, ui)
      }
      Box(Modifier.weight(1f).fillMaxWidth()) {
        loadError?.let {
          Text("Model failed to load: $it", style = Bench.Readout, color = Bench.Fault, modifier = Modifier.align(Alignment.Center))
        }
        session?.let { live ->
          ShearLineIndicator(
            ui = ui,
            lock = live.lock,
            rig = live.rig,
            onNudge = { pin -> touch.nudge(pin) },
            modifier = Modifier.align(Alignment.BottomStart).width(168.dp).padding(bottom = 8.dp),
          )
        }
      }
      ControlBar(
        ui = ui,
        preset = preset,
        onPreset = {
          preset = it
          presetTaps++ // re-tapping the current preset snaps an orbited camera back to it
        },
        onTension = { session?.setTension(it) },
        onReset = {
          touch.cancel()
          session?.reset()
        },
        modifier = Modifier.fillMaxWidth().onSizeChanged { barHeightPx = it.height },
      )
    }
  }
}

/**
 * Three lines of telemetry, refreshed twice a second.
 *
 * The state lives in here, in its own restart scope, on purpose: a changing fps number must
 * recompose this pill and nothing else. Hoisted into the screen it would recompose the
 * SceneView call twice a second, and the readout would be keeping alive the very render loop
 * it is measuring.
 */
@Composable
private fun LiveStatsPill(glb: GlbInfo, meter: FrameMeter, session: LockSession?, ui: BenchUi) {
  var lines by remember { mutableStateOf(listOf("%,d tris · %d draws".format(Locale.US, glb.triangles, glb.primitives), "— fps", "")) }
  LaunchedEffect(session) {
    var lastFrames = meter.frames
    var lastAt = SystemClock.uptimeMillis()
    while (true) {
      delay(500)
      val now = SystemClock.uptimeMillis()
      val drawn = meter.frames - lastFrames
      val fps = drawn * 1000f / (now - lastAt).coerceAtLeast(1)
      lastFrames = meter.frames
      lastAt = now
      // SceneView renders on demand: no frames means parked, not slow.
      val rate = if (now - meter.lastFrameAt > 600) "idle · on demand" else "%.0f fps".format(Locale.US, fps)
      val active = session?.let { live ->
        val pin = ui.selectedPin
        if (pin >= 0) {
          // 1 model unit = 100 mm.
          "%s Δy %+.1f mm".format(Locale.US, live.rig.nodeName(pin), live.lock.keyLift[pin] * 100f)
        } else {
          "Core_Plug rz %+.1f°".format(Locale.US, live.pose.plugDegrees)
        }
      } ?: "loading model"
      lines = listOf("%,d tris · %d draws".format(Locale.US, glb.triangles, glb.primitives), rate, active)
    }
  }
  StatsPill(lines)
}

/**
 * Haptics: a firm click as each pin sets, a pattern for open and for a mistake, and a light
 * tick as the pins drop.
 *
 * The set uses VIRTUAL_KEY because it plays the platform's full CLICK effect. CLOCK_TICK reads
 * like the right name but maps to TEXTURE_TICK, the faintest effect there is — barely felt
 * on a Pixel.
 */
private fun View.click(event: LockEvent) {
  val modern = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
  val effect = when (event) {
    is LockEvent.PinSet -> HapticFeedbackConstants.VIRTUAL_KEY
    LockEvent.Opened -> if (modern) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.LONG_PRESS
    is LockEvent.PinOverset -> if (modern) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS
    LockEvent.Dropped -> HapticFeedbackConstants.KEYBOARD_RELEASE
    LockEvent.Relocked -> return
  }
  performHapticFeedback(effect)
}

/**
 * Turns touches on the 3D view into pin presses.
 *
 * A touch that lands on a pin stack (SceneView's collision system does the ray test) is
 * consumed here, so it never reaches the orbit camera: drag up to lift the pin, or tap to
 * nudge it to the shear line. Any other touch falls through and moves the camera.
 */
private class PinTouch(
  private val collisionSystem: CollisionSystem,
  hostView: View,
  private val scope: CoroutineScope,
) {
  var session: LockSession? = null
  var dragRangePx = 300f

  private val slop = ViewConfiguration.get(hostView.context).scaledTouchSlop
  private var pin = -1
  private var downY = 0f
  private var dragging = false
  private var nudgeJob: Job? = null

  fun handle(event: MotionEvent): Boolean {
    val live = session ?: return false
    when (event.actionMasked) {
      MotionEvent.ACTION_DOWN -> {
        pin = if (live.lock.opened) -1 else live.rig.pinAt(collisionSystem.hitTest(event))
        if (pin < 0) return false
        nudgeJob?.cancel()
        downY = event.y
        dragging = false
        live.hold(pin, 0f)
        return true
      }
      MotionEvent.ACTION_MOVE -> {
        if (pin < 0) return false
        val rise = downY - event.y
        if (!dragging && abs(rise) > slop) dragging = true
        if (dragging) live.hold(pin, rise / dragRangePx * live.lock.maxLift(pin))
        return true
      }
      MotionEvent.ACTION_UP -> {
        if (pin < 0) return false
        val tapped = pin
        pin = -1
        if (dragging) live.letGo() else nudge(tapped)
        return true
      }
      MotionEvent.ACTION_CANCEL -> {
        if (pin < 0) return false
        pin = -1
        live.letGo()
        return true
      }
      // A second finger joining a pin drag is still ours, not a pinch-zoom.
      else -> return pin >= 0
    }
  }

  fun nudge(target: Int) {
    val live = session ?: return
    nudgeJob?.cancel()
    nudgeJob = scope.launch { live.nudge(target) }
  }

  fun cancel() {
    nudgeJob?.cancel()
    pin = -1
  }
}
