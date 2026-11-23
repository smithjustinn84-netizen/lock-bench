package com.lockpicking.bench.domain.lock

/** Where the torque on the plug sits, as far as anything on screen needs to say. */
enum class TorqueBand {
  /** Not enough to hold the plug against the pins. */
  Slack,

  /** Light enough for a spool to turn the plug back. */
  Eased,

  /** Working tension: pins bind and set, spools hold fast. */
  Firm,

  /** So heavy the binding pin will not lift. */
  Heavy,
}
