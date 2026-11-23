package com.lockpicking.bench.ui.bench.scene

import com.lockpicking.bench.ui.bench.CameraPreset
import io.github.sceneview.math.Position

/**
 * Eye position in model units. Selecting a preset glides the camera here. Close in: the lock
 * has only the strip between the two thumb zones of a landscape screen to itself.
 */
internal val CameraPreset.eye: Position
  get() = when (this) {
    CameraPreset.Isometric -> Position(1.06f, 0.59f, 0.69f)
    CameraPreset.KeywayFront -> Position(0f, 0.13f, 1.36f)
    CameraPreset.ShearSide -> Position(1.04f, 0.11f, 0f)
  }

/** The point the camera orbits and looks at, in model units. */
internal val CameraPreset.target: Position
  get() = when (this) {
    CameraPreset.Isometric, CameraPreset.KeywayFront -> Position(0f, 0.17f, 0f)
    CameraPreset.ShearSide -> Position(0f, 0.11f, 0f)
  }
