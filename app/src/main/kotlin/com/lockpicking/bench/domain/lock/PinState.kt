package com.lockpicking.bench.domain.lock

enum class PinState {
  /** Driver straddles the shear line and blocks the plug. */
  Resting,

  /** Driver is trapped above the shear line on the ledge of the turned plug. */
  Set,

  /** The key pin itself was pushed across the shear line and is jammed there. */
  Overset,
}
