package com.lockpicking.bench.domain.lock

/**
 * One pin stack, in model units (1 unit = 0.1 m). Read from the glTF node extras.
 *
 * @property liftToShear how far the key pin must rise for the gap between it and its driver
 *   to sit exactly on the shear line.
 * @property springRest the spring's uncompressed length, used to scale it as the driver rises.
 * @property spool the driver has a narrow waist: the plug can turn into it, a false set.
 * @property serrated the driver has a ladder of fine grooves that catch on the shear line.
 */
data class PinSpec(
  val number: Int,
  val liftToShear: Float,
  val springRest: Float,
  val spool: Boolean = false,
  val serrated: Boolean = false,
) {
  init {
    require(!(spool && serrated)) { "a driver is a spool or serrated, not both" }
  }
}
