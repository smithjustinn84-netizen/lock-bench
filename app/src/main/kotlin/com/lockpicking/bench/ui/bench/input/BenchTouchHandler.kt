package com.lockpicking.bench.ui.bench.input

import android.view.MotionEvent

/**
 * Feeds the 3D view's raw touches to a [BenchTouchRouter], pointer by pointer.
 *
 * The view gets every finger on the screen in one stream, the ones on the HUD drawn over it
 * included, so this is where a thumb on the wrench and a finger on a pin are told apart.
 */
class BenchTouchHandler(val router: BenchTouchRouter) {

  /**
   * Returns true if [event] belongs to the bench and must not reach the orbit camera. [pinAt]
   * ray-tests a point in the view against the pin stacks and returns the pin under it, or -1.
   */
  fun handle(event: MotionEvent, pinAt: (Float, Float) -> Int): Boolean = when (event.actionMasked) {
    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
      val index = event.actionIndex
      router.onDown(event.getPointerId(index), event.getX(index), event.getY(index), pinAt)
    }
    MotionEvent.ACTION_MOVE -> {
      for (index in 0 until event.pointerCount) router.onMove(event.getPointerId(index), event.getY(index))
      router.ownsGesture
    }
    MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> router.onUp(event.getPointerId(event.actionIndex))
    MotionEvent.ACTION_CANCEL -> router.onCancel()
    else -> router.ownsGesture
  }
}
