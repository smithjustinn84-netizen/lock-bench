package com.lockpicking.bench.domain.model

import com.lockpicking.bench.domain.lock.PinSpec

/**
 * The dimensions of the lock that the simulation and the 2D readout need, in model units
 * (1 unit = 0.1 m). Read from the 3D model when it binds, so nothing here is hard-coded; held
 * as plain data so the logic that uses it never touches a scene node.
 */
data class LockGeometry(
  val pinSpecs: List<PinSpec>,
  /** World Z of each pin stack. */
  val pinZ: List<Float>,
  /** The model's node name for each key pin, for telemetry. */
  val pinNodeNames: List<String>,
  /** Height of the key pin tips at rest. */
  val pinRestY: Float,
  val shearY: Float,
  val chamberTop: Float,
  val driverLength: Float,
  /** Signed rotation of a fully turned plug, degrees. */
  val openDegrees: Float,
  /** How far the shackle must rise for its toe to clear the housing. */
  val shackleTravel: Float,
  /** Where the pick waits when no pin is selected. */
  val pickParkedZ: Float,
  val pickParkedY: Float,
) {
  init {
    require(pinZ.size == pinSpecs.size && pinNodeNames.size == pinSpecs.size) {
      "every pin needs a position and a name"
    }
  }

  val pinCount: Int get() = pinSpecs.size

  companion object {
    /** How many pin stacks to lay the HUD out for before a model has bound and said how many it has. */
    const val DEFAULT_PIN_COUNT = 4
  }
}
