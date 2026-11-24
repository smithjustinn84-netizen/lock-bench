package com.lockpicking.bench.ui.bench

import androidx.annotation.StringRes
import com.lockpicking.bench.R

/** The camera angles the bench offers. Where each one puts the camera is the scene's business. */
enum class CameraPreset(@param:StringRes val label: Int, val inDrawer: Boolean = true) {
  Isometric(R.string.camera_isometric),
  KeywayFront(R.string.camera_keyway),
  ShearSide(R.string.camera_shear),

  /**
   * Picking blind: the keyway face from the solid side. The cutaway notch runs through the
   * front face, so even head-on the cut half shows; from here the uncut half hides it.
   */
  Blind(R.string.camera_keyway, inDrawer = false),
}
