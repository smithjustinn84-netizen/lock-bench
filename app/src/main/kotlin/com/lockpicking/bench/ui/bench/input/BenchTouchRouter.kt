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
 * Sorts the fingers on the 3D view into a hand on the wrench, a pick under a pin, and the
 * orbit camera.
 *
 * The first finger down decides who the whole gesture belongs to. If it lands on the wrench
 * lever or on a pin stack, the bench owns every finger until all of them are up, and each
 * later finger is given to the wrench or to a pin by where it lands. If it lands anywhere
 * else the camera owns the gesture and the bench ignores it, so the camera never sees one
 * finger of a two-finger pick as half of a pinch.
 *
 * Knows nothing of Android: pointers are ids and pixels.
 *
 * @param touchSlop how far a finger may wander before a tap becomes a drag, in pixels.
 */
class BenchTouchRouter(
  private val pins: PinInput,
  private val wrench: WrenchInput,
  private val touchSlop: Float,
) {
  /** Finger travel, in pixels, that lifts a pin through its full range. */
  var dragRangePx = DEFAULT_DRAG_RANGE_PX

  /** Finger travel, in pixels, that pulls the wrench from nothing to full torque. */
  var wrenchThrowPx = DEFAULT_WRENCH_THROW_PX

  /** Where the wrench lever is drawn. */
  var wrenchBounds = TouchRect.Empty

  /** Panels drawn over the 3D view that take their own touches: no pin behind one can be hit. */
  var blocked: List<TouchRect> = emptyList()

  private enum class Owner { None, Bench, Camera }

  private var owner = Owner.None
  private val down = HashSet<Int>()

  private var wrenchPointer = NONE
  private var wrenchDownY = 0f
  private var wrenchAmount = -1f

  private var pinPointer = NONE
  private var pin = -1
  private var pinDownY = 0f
  private var dragging = false

  /** True while the gesture in progress belongs to the bench and not the camera. */
  val ownsGesture: Boolean get() = owner == Owner.Bench

  /**
   * A finger went down. [pinAt] ray-tests a point against the pin stacks and returns the
   * 0-based pin under it, or -1. Returns true if the gesture belongs to the bench.
   */
  fun onDown(id: Int, x: Float, y: Float, pinAt: (Float, Float) -> Int): Boolean {
    down += id
    if (owner == Owner.Camera) return false
    val first = owner == Owner.None
    when {
      wrenchBounds.contains(x, y) -> if (wrenchPointer == NONE) {
        owner = Owner.Bench
        wrenchPointer = id
        wrenchDownY = y
        wrenchAmount = 0f
        wrench.onWrenchPulled(0f)
      }
      blocked.any { it.contains(x, y) } -> Unit
      pinPointer == NONE && pins.acceptsPins -> {
        val hit = pinAt(x, y)
        if (hit >= 0) {
          owner = Owner.Bench
          pinPointer = id
          pin = hit
          pinDownY = y
          dragging = false
          pins.onPinPressed(hit)
        }
      }
    }
    if (first && owner == Owner.None) owner = Owner.Camera
    return owner == Owner.Bench
  }

  /** A finger moved. Returns true if the gesture belongs to the bench. */
  fun onMove(id: Int, y: Float): Boolean {
    if (owner != Owner.Bench) return false
    if (id == wrenchPointer) {
      val amount = WrenchThrow.torqueAt((y - wrenchDownY) / wrenchThrowPx)
      if (amount != wrenchAmount) {
        wrenchAmount = amount
        wrench.onWrenchPulled(amount)
      }
    } else if (id == pinPointer) {
      val rise = pinDownY - y
      if (!dragging && abs(rise) > touchSlop) dragging = true
      if (dragging) pins.onPinDragged(pin, rise / dragRangePx)
    }
    return true
  }

  /** A finger came up. Returns true if the gesture belonged to the bench. */
  fun onUp(id: Int): Boolean {
    val ours = owner == Owner.Bench
    if (id == wrenchPointer) {
      wrenchPointer = NONE
      wrench.onWrenchReleased()
    } else if (id == pinPointer) {
      val tapped = pin
      val dragged = dragging
      pinPointer = NONE
      pin = -1
      if (dragged) pins.onPinReleased() else pins.onPinTapped(tapped)
    }
    down -= id
    if (down.isEmpty()) owner = Owner.None
    return ours
  }

  /** The system took the gesture away: let go of everything. Returns true if it was the bench's. */
  fun onCancel(): Boolean {
    val ours = owner == Owner.Bench
    letGo()
    down.clear()
    owner = Owner.None
    return ours
  }

  /**
   * Let go of the wrench and the pin without ending the gesture: the fingers still down stay
   * the bench's, so they do not start moving the camera, but do nothing more.
   */
  fun letGo() {
    if (wrenchPointer != NONE) {
      wrenchPointer = NONE
      wrench.onWrenchReleased()
    }
    if (pinPointer != NONE) {
      pinPointer = NONE
      pin = -1
      pins.onPinReleased()
    }
  }

  private companion object {
    const val NONE = -1
    const val DEFAULT_DRAG_RANGE_PX = 300f
    const val DEFAULT_WRENCH_THROW_PX = 260f
  }
}
