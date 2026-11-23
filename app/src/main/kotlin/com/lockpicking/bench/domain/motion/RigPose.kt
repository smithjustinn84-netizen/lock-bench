package com.lockpicking.bench.domain.motion

/** Everything about the model's pose that is not pin travel. Written by the simulation each frame. */
class RigPose {
  /** Signed plug rotation about Z, degrees. */
  var plugDegrees = 0f
    internal set

  /** 0 = assembled, 1 = fully exploded. */
  var explode = 0f
    internal set
  var shackleLift = 0f
    internal set
  var shackleSwingDegrees = 0f
    internal set

  /** 0 = wrench withdrawn (hidden), 1 = seated in the keyway. */
  var wrenchSeat = 0f
    internal set

  /** World Z and height of the hook tip. */
  var pickZ = 0f
    internal set
  var pickY = 0f
    internal set

  /** Housing opacity: 1 = solid, lower = ghosted. */
  var bodyAlpha = 1f
    internal set
}
