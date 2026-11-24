package com.lockpicking.bench.ui.bench.input

import kotlin.math.abs

/** A rectangle in the 3D view's pixels. */
data class TouchRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
  fun contains(x: Float, y: Float): Boolean = x >= left && x < right && y >= top && y < bottom

  companion object {
    val Empty = TouchRect(0f, 0f, 0f, 0f)
  }
}

/**
 * Sorts the fingers on the 3D view into a hand on the wrench, a hand on the pick, and the
 * orbit camera.
 *
 * The first finger down decides who the whole gesture belongs to. If it lands on the tension
 * zone or the pick pad, the bench owns every finger until all of them are up, and each later
 * finger is given to the wrench or to the pick by where it lands. If it lands anywhere else
 * the camera owns the gesture and the bench ignores it, so the camera never sees one thumb of
 * a two-thumb pick as half of a pinch.
 *
 * A camera gesture that turns out to be one finger down and up again without moving is a tap
 * on the scene, reported to [onSceneTap]. While [cameraLocked] the camera's gestures are
 * swallowed, so nothing orbits, but a tap still gets through.
 *
 * The pick pad works like a trackpad. Travel across it slides the pick along the keyway from
 * wherever it already was, and travel up it lifts the tip from wherever the thumb landed, so
 * the thumb can come off and go back on without losing its place.
 *
 * Knows nothing of Android: pointers are ids and pixels.
 *
 * @param touchSlop how far a thumb may wander before a tap becomes a drag, in pixels. It is
 *   also the dead band at the bottom of the lift, so sliding the pick does not lift it.
 */
class BenchTouchRouter(
  private val pick: PickInput,
  private val wrench: WrenchInput,
  private val touchSlop: Float,
) {
  /** Thumb travel, in pixels, that lifts the tip through its full range. */
  var dragRangePx = DEFAULT_DRAG_RANGE_PX

  /** Thumb travel, in pixels, that takes the pick from the mouth of the keyway to its back wall. */
  var depthRangePx = DEFAULT_DEPTH_RANGE_PX

  /** Thumb travel, in pixels, that pulls the wrench from nothing to full torque. */
  var wrenchThrowPx = DEFAULT_WRENCH_THROW_PX

  /** The tension zone: a thumb that lands here is a hand on the wrench. */
  var wrenchBounds = TouchRect.Empty

  /** The pick pad: a thumb that lands here is a hand on the pick. */
  var pickBounds = TouchRect.Empty

  /** Panels drawn over the 3D view that take their own touches. */
  var blocked: List<TouchRect> = emptyList()

  /** Holds the camera still: gestures that would orbit or zoom it are swallowed. */
  var cameraLocked = false

  /** Hears a tap on the scene outside the thumb zones, in the 3D view's pixels. */
  var onSceneTap: ((x: Float, y: Float) -> Unit)? = null

  private enum class Owner { None, Bench, Camera }

  private var owner = Owner.None
  private val down = HashSet<Int>()

  private var wrenchPointer = NONE
  private var wrenchDownY = 0f
  private var wrenchAmount = -1f

  private var pickPointer = NONE
  private var pickDownX = 0f
  private var pickDownY = 0f

  /** Where the pick was along the keyway when the thumb landed. */
  private var depthAtDown = 0f

  /** The lowest the thumb has been this touch: lift is measured up from there. */
  private var pickFloorY = 0f
  private var dragging = false

  /** The finger that may yet turn out to be a tap on the scene, or [NONE]. */
  private var tapPointer = NONE
  private var tapX = 0f
  private var tapY = 0f

  /** True while the gesture in progress belongs to the bench and not the camera. */
  val ownsGesture: Boolean get() = owner == Owner.Bench

  /** True while the gesture in progress must not reach the orbit camera. */
  val consumesGesture: Boolean get() = owner == Owner.Bench || (owner == Owner.Camera && cameraLocked)

  /** A finger went down. Returns true if the gesture belongs to the bench. */
  fun onDown(id: Int, x: Float, y: Float): Boolean {
    down += id
    if (owner == Owner.Camera) {
      // A second finger: a pinch or a two-finger drag, not a tap.
      tapPointer = NONE
      return cameraLocked
    }
    val first = owner == Owner.None
    when {
      blocked.any { it.contains(x, y) } -> Unit

      wrenchBounds.contains(x, y) -> if (wrenchPointer == NONE) {
        owner = Owner.Bench
        wrenchPointer = id
        wrenchDownY = y
        wrenchAmount = 0f
        wrench.onWrenchPulled(0f)
      }

      pickBounds.contains(x, y) -> if (pickPointer == NONE) {
        owner = Owner.Bench
        pickPointer = id
        pickDownX = x
        pickDownY = y
        pickFloorY = y
        depthAtDown = pick.pickDepth
        dragging = false
      }
    }
    if (first && owner == Owner.None) {
      owner = Owner.Camera
      if (blocked.none { it.contains(x, y) }) {
        tapPointer = id
        tapX = x
        tapY = y
      }
      return cameraLocked
    }
    return owner == Owner.Bench
  }

  /** A finger moved. Returns true if the gesture belongs to the bench. */
  fun onMove(id: Int, x: Float, y: Float): Boolean {
    if (owner == Owner.Camera) {
      if (id == tapPointer && (abs(x - tapX) > touchSlop || abs(y - tapY) > touchSlop)) tapPointer = NONE
      return cameraLocked
    }
    if (owner != Owner.Bench) return false
    if (id == wrenchPointer) {
      val amount = WrenchThrow.torqueAt((y - wrenchDownY) / wrenchThrowPx)
      if (amount != wrenchAmount) {
        wrenchAmount = amount
        wrench.onWrenchPulled(amount)
      }
    } else if (id == pickPointer) {
      if (!dragging && (abs(x - pickDownX) > touchSlop || abs(y - pickDownY) > touchSlop)) dragging = true
      if (!dragging) return true
      var depth = depthAtDown + (x - pickDownX) / depthRangePx
      // A thumb that runs on past the end of the keyway does not have to come all the way back.
      val over = if (depth > 1f + OVERRUN) depth - (1f + OVERRUN) else if (depth < -OVERRUN) depth + OVERRUN else 0f
      if (over != 0f) {
        pickDownX += over * depthRangePx
        depth -= over
      }
      if (y > pickFloorY) pickFloorY = y
      val lift = ((pickFloorY - y - touchSlop) / dragRangePx).coerceIn(0f, MAX_LIFT)
      pick.onPickMoved(depth, lift)
    }
    return true
  }

  /** A finger came up. Returns true if the gesture belonged to the bench. */
  fun onUp(id: Int): Boolean {
    val ours = owner == Owner.Bench
    val camera = owner == Owner.Camera
    if (camera && id == tapPointer) {
      tapPointer = NONE
      onSceneTap?.invoke(tapX, tapY)
    }
    if (id == wrenchPointer) {
      wrenchPointer = NONE
      wrench.onWrenchReleased()
    } else if (id == pickPointer) {
      pickPointer = NONE
      if (dragging) {
        pick.onPickRested()
      } else {
        pick.onPickTapped(((pickDownX - pickBounds.left) / (pickBounds.right - pickBounds.left)).coerceIn(0f, 1f))
      }
    }
    down -= id
    if (down.isEmpty()) owner = Owner.None
    return ours || (camera && cameraLocked)
  }

  /** The system took the gesture away: let go of everything. Returns true if it was the bench's. */
  fun onCancel(): Boolean {
    val ours = owner == Owner.Bench || (owner == Owner.Camera && cameraLocked)
    tapPointer = NONE
    letGo()
    down.clear()
    owner = Owner.None
    return ours
  }

  /**
   * Let go of the wrench and the pick without ending the gesture: the fingers still down stay
   * the bench's, so they do not start moving the camera, but do nothing more.
   */
  fun letGo() {
    if (wrenchPointer != NONE) {
      wrenchPointer = NONE
      wrench.onWrenchReleased()
    }
    if (pickPointer != NONE) {
      pickPointer = NONE
      pick.onPickRested()
    }
  }

  /** Let go of the pick alone: it is being swapped for another, and the wrench hand is not in on that. */
  fun letGoOfPick() {
    if (pickPointer != NONE) {
      pickPointer = NONE
      pick.onPickRested()
    }
  }

  private companion object {
    const val NONE = -1
    const val DEFAULT_DRAG_RANGE_PX = 300f
    const val DEFAULT_DEPTH_RANGE_PX = 440f
    const val DEFAULT_WRENCH_THROW_PX = 260f

    /** How far past either end of the keyway the thumb can push, as a share of its length. */
    const val OVERRUN = 0.06f

    /** The top of the lift, a little past full travel so every pin can be run into its stop. */
    const val MAX_LIFT = 1.1f
  }
}
