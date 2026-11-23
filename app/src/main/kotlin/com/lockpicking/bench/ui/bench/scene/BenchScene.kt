package com.lockpicking.bench.ui.bench.scene

import android.util.Log
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import com.lockpicking.bench.data.AssetPaths
import com.lockpicking.bench.data.glb.GlbInfo
import com.lockpicking.bench.ui.bench.CameraPreset
import com.lockpicking.bench.ui.bench.FrameMeter
import com.lockpicking.bench.ui.bench.input.BenchTouchHandler
import io.github.sceneview.ExperimentalSceneViewApi
import io.github.sceneview.SceneView
import io.github.sceneview.environment.rememberHDREnvironment
import io.github.sceneview.math.Direction
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

private const val TAG = "BenchScene"

/** The dark backdrop behind the lock, linear RGBA. The studio HDR lights the room but is not drawn. */
private val CLEAR_COLOR = doubleArrayOf(0.014, 0.016, 0.020, 1.0)

/**
 * The 3D bench: the padlock model under a key light and the studio environment, an orbit
 * camera, and pin hit-testing. Every touch on the screen arrives here, the ones on the HUD's
 * wrench lever included; [touch] decides which are the bench's and which move the camera.
 *
 * Everything Filament owns lives here and dies with the composition. Once the model binds,
 * [host] gets the lock's geometry and drives the rig through its frame loop.
 */
@OptIn(ExperimentalSceneViewApi::class)
@Composable
fun BenchScene(
  host: BenchSceneHost,
  modelInfo: GlbInfo?,
  preset: CameraPreset,
  presetResetKey: Int,
  contentPadding: PaddingValues,
  meter: FrameMeter,
  touch: BenchTouchHandler,
  modifier: Modifier = Modifier,
) {
  val engine = rememberEngine()
  val modelLoader = rememberModelLoader(engine)
  val environmentLoader = rememberEnvironmentLoader(engine)
  val view = rememberView(engine)
  val renderer = rememberRenderer(engine)
  val collisionSystem = rememberCollisionSystem(view)
  val invalidator = rememberRenderInvalidator()

  remember(renderer) {
    renderer.clearOptions = renderer.clearOptions.apply {
      clear = true
      clearColor = CLEAR_COLOR
    }
  }

  val cameraNode = rememberCameraNode(engine) {
    position = preset.eye
    lookAt(preset.target)
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
  val studio = rememberHDREnvironment(environmentLoader, AssetPaths.STUDIO_ENVIRONMENT, createSkybox = false)
  val neutral = rememberEnvironment(environmentLoader)

  val manipulator = rememberCameraManipulator(
    orbitHomePosition = preset.eye,
    targetPosition = preset.target,
    key = presetResetKey,
  )

  var rig by remember { mutableStateOf<LockRig?>(null) }

  SceneView(
    modifier = modifier,
    engine = engine,
    modelLoader = modelLoader,
    environmentLoader = environmentLoader,
    view = view,
    renderer = renderer,
    renderInvalidator = invalidator,
    // The model is authored around the plug axis and the rig poses it in those coordinates.
    autoCenterContent = false,
    // Frame the lock in what the HUD leaves visible, without resizing the surface.
    contentPadding = contentPadding,
    environment = studio ?: neutral,
    mainLightNode = keyLight,
    fillLightNode = null,
    cameraNode = cameraNode,
    collisionSystem = collisionSystem,
    cameraManipulator = manipulator,
    onTouchEvent = { event, _ ->
      touch.handle(event) { x, y -> rig?.pinAt(collisionSystem.hitTest(x, y)) ?: -1 }
    },
    onFrame = { meter.onFrame() },
  ) {
    rememberModelInstance(modelLoader, AssetPaths.MODEL)?.let { instance ->
      val holder = remember(instance) { arrayOfNulls<ModelNode>(1) }
      ModelNode(modelInstance = instance, autoAnimate = false, apply = { holder[0] = this })
      modelInfo?.let { info ->
        LaunchedEffect(instance, info) {
          val node = holder[0] ?: return@LaunchedEffect
          runCatching { LockRig(node, info) }
            .onSuccess {
              rig = it
              host.onModelBound(it.geometry)
            }
            .onFailure {
              Log.e(TAG, "could not bind the lock model", it)
              host.onModelFailed(it.message ?: it.toString())
            }
        }
      }
    }
  }

  rig?.let { live ->
    LaunchedEffect(live, host) {
      host.runFrameLoop({ withFrameNanos { it } }) { pose, pins ->
        // A material write goes straight into Filament, where SceneView cannot see it.
        if (live.apply(pose, pins)) invalidator.requestRender()
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
}
