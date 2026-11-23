package com.lockpicking.bench.ui.bench.scene

import com.lockpicking.bench.domain.lock.LockReadout
import com.lockpicking.bench.domain.model.LockGeometry
import com.lockpicking.bench.domain.motion.FrameClock
import com.lockpicking.bench.domain.motion.RigPose

/** What the 3D scene reports to, and is driven by. */
interface BenchSceneHost {
  /** The model is bound; these are the lock's dimensions as it authors them. */
  fun onModelBound(geometry: LockGeometry)

  fun onModelFailed(message: String)

  /** Runs the bench on [clock] until cancelled, handing each frame's pose to [onFrame]. */
  suspend fun runFrameLoop(clock: FrameClock, onFrame: (RigPose, LockReadout) -> Unit)
}
