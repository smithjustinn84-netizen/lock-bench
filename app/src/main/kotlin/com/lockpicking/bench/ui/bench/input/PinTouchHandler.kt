package com.lockpicking.bench.ui.bench.input

import android.view.MotionEvent
import kotlin.math.abs

/**
 * Turns touches on the 3D view into pin gestures.
 *
 * A touch that lands on a pin stack is consumed here, so it never reaches the orbit camera:
 * drag up to lift the pin, or tap to nudge it to the shear line. Any other touch falls through
 * and moves the camera.
 *
 * @param touchSlop how far a finger may wander before a tap becomes a drag, in pixels.
 */
class PinTouchHandler(
  private val input: PinInput,
  private val touchSlop: Float,
) {
  /** Finger travel, in pixels, that lifts a pin through its full range. */
  var dragRangePx = DEFAULT_DRAG_RANGE_PX

  private var pin = -1
  private var downY = 0f
  private var dragging = false

  /**
   * Returns true if [event] belongs to a pin gesture. [pinAt] ray-tests the touch against the
   * pin stacks and returns the 0-based pin under it, or -1.
   */
  fun handle(event: MotionEvent, pinAt: (MotionEvent) -> Int): Boolean {
    when (event.actionMasked) {
      MotionEvent.ACTION_DOWN -> {
        pin = if (input.acceptsPins) pinAt(event) else -1
        if (pin < 0) return false
        downY = event.y
        dragging = false
        input.onPinPressed(pin)
        return true
      }
      MotionEvent.ACTION_MOVE -> {
        if (pin < 0) return false
        val rise = downY - event.y
        if (!dragging && abs(rise) > touchSlop) dragging = true
        if (dragging) input.onPinDragged(pin, rise / dragRangePx)
        return true
      }
      MotionEvent.ACTION_UP -> {
        if (pin < 0) return false
        val tapped = pin
        pin = -1
        if (dragging) input.onPinReleased() else input.onPinTapped(tapped)
        return true
      }
      MotionEvent.ACTION_CANCEL -> {
        if (pin < 0) return false
        pin = -1
        input.onPinReleased()
        return true
      }
      // A second finger joining a pin drag is still ours, not a pinch-zoom.
      else -> return pin >= 0
    }
  }

  /** Forget the gesture in progress, so its remaining moves are ignored. */
  fun cancel() {
    pin = -1
  }

  private companion object {
    const val DEFAULT_DRAG_RANGE_PX = 300f
  }
}
