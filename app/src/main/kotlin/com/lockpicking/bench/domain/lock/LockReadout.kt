package com.lockpicking.bench.domain.lock

/**
 * A read-only view of the pin stacks, for whatever draws or poses them. Readers see live
 * values: nothing is copied per frame.
 */
interface LockReadout {
  val pinCount: Int

  fun pin(index: Int): PinSpec

  /** How far the key pin at [index] has risen from rest, in model units. */
  fun keyLift(index: Int): Float

  /** How far the driver at [index] has risen from rest, in model units. */
  fun driverLift(index: Int): Float

  fun state(index: Int): PinState
}
