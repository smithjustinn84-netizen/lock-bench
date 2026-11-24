package com.lockpicking.bench.domain.game

/** Milliseconds on a monotonic clock, for timing rounds. */
fun interface GameClock {
  fun nowMillis(): Long
}
