package com.lockpicking.bench.domain.motion

/**
 * The display's frame timeline. In the app this is Compose's `withFrameNanos`; tests drive it
 * by hand.
 */
fun interface FrameClock {
  /** Suspends until the next frame and returns its timestamp in nanoseconds. */
  suspend fun awaitFrameNanos(): Long
}
