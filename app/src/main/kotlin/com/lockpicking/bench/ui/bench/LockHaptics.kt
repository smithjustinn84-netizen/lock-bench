package com.lockpicking.bench.ui.bench

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import com.lockpicking.bench.domain.lock.LockEvent

/**
 * Haptics: a firm click as each pin sets, a pattern for open and for a mistake, and a light
 * tick as the pins drop.
 *
 * The set uses VIRTUAL_KEY because it plays the platform's full CLICK effect. CLOCK_TICK reads
 * like the right name but maps to TEXTURE_TICK, the faintest effect there is — barely felt
 * on a Pixel.
 */
fun View.performLockHaptic(event: LockEvent) {
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
