package com.lockpicking.bench.ui.bench.scene

import android.util.Log
import com.google.android.filament.MaterialInstance
import com.lockpicking.bench.data.glb.GlbInfo
import com.lockpicking.bench.domain.lock.LockReadout
import com.lockpicking.bench.domain.lock.PinSpec
import com.lockpicking.bench.domain.model.LockGeometry
import com.lockpicking.bench.domain.motion.RigPose
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Scale
import io.github.sceneview.node.ModelNode
import io.github.sceneview.node.Node
import io.github.sceneview.node.RenderableNode
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sign

/**
 * Binds the named nodes of `cutaway_padlock.glb` and poses them.
 *
 * The .glb is the source of truth for geometry: rest positions are captured from the nodes, and
 * the travel each part needs comes from the glTF `extras` the Blender script wrote. Nothing
 * here knows a dimension of the lock: what the rest of the app needs is handed out as [geometry].
 *
 * Lives and dies with the scene's Filament engine, so only the composition holds one.
 */
class LockRig(modelNode: ModelNode, glb: GlbInfo) {

  /** A node plus the local position it was authored at, so offsets never accumulate. */
  private class Part(val node: Node) {
    init {
      // SceneView keeps position / rotation / scale in a Kotlin-side cache that starts at
      // identity for a node wrapped around an existing glTF entity; only `transform` reads
      // Filament. Round-tripping the matrix seeds the cache, so the per-component setters
      // below compose with the authored pose instead of replacing it.
      node.transform = node.transform
    }

    // Copied component-wise: Float3 is mutable, and this must not alias the node's own state.
    val rest: Position = node.position.let { Position(it.x, it.y, it.z) }
    private val restRotation: Rotation = node.rotation.let { Rotation(it.x, it.y, it.z) }
    private var roll = 0f
    private var dx = 0f
    private var dy = 0f
    private var dz = 0f

    /**
     * Writes only on change: every transform write wakes SceneView's on-demand render loop,
     * and a settled lock should let it park.
     */
    fun offset(x: Float = 0f, y: Float = 0f, z: Float = 0f) {
      if (abs(x - dx) < EPS && abs(y - dy) < EPS && abs(z - dz) < EPS) return
      dx = x; dy = y; dz = z
      node.position = Position(rest.x + x, rest.y + y, rest.z + z)
    }

    /** Turns the part about its own Z by [degrees] from how it was authored. Writes only on change. */
    fun roll(degrees: Float) {
      if (abs(degrees - roll) < ROLL_EPS) return
      roll = degrees
      node.rotation = Rotation(restRotation.x, restRotation.y, restRotation.z + degrees)
    }
  }

  private val byName: Map<String, Node> =
    modelNode.nodes.mapNotNull { node -> node.name?.let { it to node } }.toMap()

  /** Names the contract requires that the file does not have. Empty when the asset is good. */
  private val missing: List<String> = REQUIRED.filterNot { it in byName }

  private val nodeNames: List<String> = byName.keys.sorted()

  private val body = part("Body_Cutaway")
  private val shackle = part("Shackle")
  private val plug = part("Core_Plug")
  private val wrench = part("TensionWrench")
  private val pick = part("HookPick")
  private val keyPins = (1..PIN_COUNT).map { part("KeyPin_$it") }
  private val driverPins = (1..PIN_COUNT).map { part("DriverPin_$it") }
  private val springs = (1..PIN_COUNT).map { part("Spring_$it") }

  private val plugExtras = extras(plug.node)

  /** Height of the key pin tips at rest. */
  private val pinRestY: Float = plugExtras.optDouble("pin_rest_y", plug.rest.y.toDouble()).toFloat()
  private val pickFulcrumZ: Float = plugExtras.optDouble("pick_fulcrum_z", 0.135).toFloat()

  /** The lock's dimensions as the .glb authors them. */
  val geometry: LockGeometry = LockGeometry(
    pinSpecs = (0 until PIN_COUNT).map { i ->
      PinSpec(
        number = i + 1,
        liftToShear = extras(keyPins[i].node).getDouble("lift_to_shear").toFloat(),
        springRest = extras(springs[i].node).getDouble("rest_length").toFloat(),
        spool = extras(driverPins[i].node).optBoolean("spool", false),
      )
    },
    // The plug sits at the origin, so plug-local Z is world Z.
    pinZ = keyPins.map { it.rest.z },
    pinNodeNames = (1..PIN_COUNT).map { "KeyPin_$it" },
    pinRestY = pinRestY,
    shearY = plugExtras.optDouble("shear_y", 0.07).toFloat(),
    chamberTop = plugExtras.optDouble("chamber_top", 0.25).toFloat(),
    driverLength = plugExtras.optDouble("driver_length", 0.06).toFloat(),
    openDegrees = plugExtras.optDouble("open_degrees", -90.0).toFloat(),
    shackleTravel = extras(shackle.node).optDouble("toe_engagement", 0.055).toFloat() + SHACKLE_CLEARANCE,
    // Where the pick waits when no pin is selected, as authored.
    pickParkedZ = pick.rest.z,
    pickParkedY = pick.rest.y,
  )

  /** Which way the plug turns to open. */
  private val plugSign = sign(geometry.openDegrees)

  private val bodyMaterials: List<Pair<MaterialInstance, FloatArray>> =
    (body.node as? RenderableNode)?.materialInstances.orEmpty().map { instance ->
      instance to (glb.baseColors[instance.name] ?: floatArrayOf(0.5f, 0.5f, 0.5f, 1f))
    }
  private var appliedAlpha = Float.NaN
  private var appliedPlug = Float.NaN
  private var appliedSwing = Float.NaN
  private var appliedPickPitch = Float.NaN
  private val appliedSpring = FloatArray(PIN_COUNT) { Float.NaN }

  init {
    Log.i(TAG, "bound ${byName.size} nodes: $nodeNames")
    if (missing.isNotEmpty()) Log.e(TAG, "model is missing required nodes: $missing")
  }

  /**
   * Poses every part. Returns true if the housing material changed — a write straight into a
   * Filament MaterialInstance, which SceneView cannot see and the caller must invalidate for.
   */
  fun apply(pose: RigPose, pins: LockReadout): Boolean {
    val e = pose.explode
    // The shackle clears first, then the housing slides off sideways.
    val shackleOut = smooth(e / 0.6f)
    val bodyOut = smooth((e - 0.15f) / 0.85f)

    body.offset(x = -0.42f * bodyOut)

    shackle.offset(y = pose.shackleLift + 0.20f * shackleOut)
    if (differs(appliedSwing, pose.shackleSwingDegrees)) {
      appliedSwing = pose.shackleSwingDegrees
      shackle.node.rotation = Rotation(y = pose.shackleSwingDegrees)
    }

    if (differs(appliedPlug, pose.plugDegrees)) {
      appliedPlug = pose.plugDegrees
      plug.node.rotation = Rotation(z = pose.plugDegrees)
    }

    for (i in 0 until PIN_COUNT) {
      // Key pins are children of the plug: their Y is the plug's, and they turn with it.
      val driverLift = pins.driverLift(i)
      keyPins[i].offset(y = pins.keyLift(i) + 0.05f * e)
      driverPins[i].offset(y = driverLift + 0.15f * e)
      // In a false set the plug carries the foot of a spool round with it and cocks it over.
      driverPins[i].roll(-plugSign * pins.driverTilt(i) * SPOOL_TILT_DEGREES)
      springs[i].offset(y = 0.24f * e)
      // The spring hangs from the top of the bible, so scaling Y squeezes it against the roof.
      val rest = pins.pin(i).springRest
      val squeeze = ((rest - driverLift) / rest).coerceIn(0.25f, 1f)
      if (differs(appliedSpring[i], squeeze)) {
        appliedSpring[i] = squeeze
        springs[i].node.scale = Scale(1f, squeeze, 1f)
      }
    }

    val wrenchVisible = pose.wrenchSeat > 0.02f
    if (wrench.node.isVisible != wrenchVisible) wrench.node.isVisible = wrenchVisible
    wrench.offset(z = (1f - pose.wrenchSeat) * 0.10f + 0.14f * e)
    // The wrench is the plug's child and turns with it; under torque its handle springs on ahead.
    wrench.roll(pose.wrenchFlexDegrees)

    val pickOut = 0.20f * e
    pick.offset(y = pose.pickY - pick.rest.y, z = pose.pickZ - pick.rest.z + pickOut)
    // The pick levers on the mouth of the keyway: as the tip rises the handle drops.
    val reach = pickFulcrumZ - pose.pickZ
    val pitch = if (reach > 0.02f) Math.toDegrees(atan2(pose.pickY - pinRestY, reach).toDouble()).toFloat() else 0f
    if (differs(appliedPickPitch, pitch)) {
      appliedPickPitch = pitch
      pick.node.rotation = Rotation(x = pitch)
    }

    return applyBodyAlpha(pose.bodyAlpha)
  }

  private fun applyBodyAlpha(alpha: Float): Boolean {
    if (!differs(appliedAlpha, alpha)) return false
    appliedAlpha = alpha
    val solid = alpha > 0.995f
    for ((instance, rgba) in bodyMaterials) {
      instance.setParameter("baseColorFactor", rgba[0], rgba[1], rgba[2], rgba[3] * alpha)
      // The housing is authored as a blended material so it can fade. While it is solid it
      // must still write depth, or its own far faces would draw over its near ones.
      instance.setDepthWrite(solid)
    }
    return true
  }

  private fun part(name: String): Part =
    Part(byName[name] ?: error("cutaway_padlock.glb has no node named '$name' (found: $nodeNames)"))

  private fun extras(node: Node): JSONObject =
    (node as? ModelNode.ChildNode)?.extras?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()

  companion object {
    private const val TAG = "LockRig"
    private const val PIN_COUNT = LockGeometry.PIN_COUNT
    private const val EPS = 1e-5f
    private const val ROLL_EPS = 0.01f

    /** How far a spool driver leans when the plug has turned fully into its waist. */
    private const val SPOOL_TILT_DEGREES = 5f

    /** Extra rise beyond the toe's engagement so the swing visibly clears the housing. */
    private const val SHACKLE_CLEARANCE = 0.022f

    private val REQUIRED: List<String> = listOf("Body_Cutaway", "Shackle", "Core_Plug", "TensionWrench", "HookPick") +
      listOf("KeyPin", "DriverPin", "Spring").flatMap { kind -> (1..PIN_COUNT).map { "${kind}_$it" } }

    private fun differs(a: Float, b: Float) = a.isNaN() || abs(a - b) > EPS

    private fun smooth(t: Float): Float {
      val x = t.coerceIn(0f, 1f)
      return x * x * (3f - 2f * x)
    }
  }
}
