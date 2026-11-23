package com.lockpicking.bench.domain.lock

/**
 * One pin stack, in model units (1 unit = 0.1 m). Read from the glTF node extras.
 *
 * @property liftToShear how far the key pin must rise for the gap between it and its driver
 *   to sit exactly on the shear line.
 * @property springRest the spring's uncompressed length, used to scale it as the driver rises.
 */
data class PinSpec(
  val number: Int,
  val liftToShear: Float,
  val springRest: Float,
  val spool: Boolean = false,
)
