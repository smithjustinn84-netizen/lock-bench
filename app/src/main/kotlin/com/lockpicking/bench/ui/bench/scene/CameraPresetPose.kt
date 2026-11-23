package com.lockpicking.bench.ui.bench.scene

import com.lockpicking.bench.ui.bench.CameraPreset
import io.github.sceneview.math.Position

/** Eye position in model units. Selecting a preset glides the camera here. */
internal val CameraPreset.eye: Position
  get() = when (this) {
    CameraPreset.Isometric -> Position(1.32f, 0.70f, 0.86f)
    CameraPreset.KeywayFront -> Position(0f, 0.12f, 1.70f)
    CameraPreset.ShearSide -> Position(1.30f, 0.11f, 0f)
  }

/** The point the camera orbits and looks at, in model units. */
internal val CameraPreset.target: Position
  get() = when (this) {
    CameraPreset.Isometric, CameraPreset.KeywayFront -> Position(0f, 0.17f, 0f)
    CameraPreset.ShearSide -> Position(0f, 0.11f, 0f)
  }
