package com.lockpicking.bench.lock

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sqrt

/** A value chasing a target on a damped spring. Critically damped unless told otherwise. */
private class Eased(
  initial: Float,
  private val stiffness: Float,
  private val damping: Float = 2f * sqrt(stiffness),
  private val epsilon: Float,
) {
  var value = initial
    private set
  var target = initial
  private var velocity = 0f

  fun snap(to: Float) {
    value = to
    target = to
    velocity = 0f
  }

  /** Returns true while still moving. */
  fun step(dt: Float): Boolean {
    if (value == target && velocity == 0f) return false
    var remaining = dt
    while (remaining > 0f) {
      val h = min(remaining, MAX_STEP)
      velocity += (stiffness * (target - value) - damping * velocity) * h
      value += velocity * h
      remaining -= h
    }
    if (abs(target - value) < epsilon && abs(velocity) < epsilon * 20f) {
      value = target
      velocity = 0f
      return false
    }
    return true
  }

  private companion object {
    const val MAX_STEP = 1f / 120f
  }
}

/** What the HUD reads. Snapshot state, written only when a value actually changes. */
@Stable
class BenchUi {
  var explode by mutableFloatStateOf(0f)
  var ghost by mutableStateOf(false)
  var tension by mutableStateOf(false)
  var opened by mutableStateOf(false)
  var setCount by mutableIntStateOf(0)
  var bindingPin by mutableIntStateOf(-1)
  var oversetPin by mutableIntStateOf(-1)
  var selectedPin by mutableIntStateOf(-1)

  /** Bumped on every frame in which a pin moved; the shear-line canvas redraws on it. */
  var motionTick by mutableIntStateOf(0)

  /** Bumped when a pin sets, to flash the shear line. */
  var setPulse by mutableIntStateOf(0)
}

/**
 * Owns the lock's state and turns it into a pose, one display frame at a time.
 *
 * The loop in [run] sleeps whenever nothing is moving. SceneView renders on demand, so a
 * settled bench costs no frames at all; every input calls [wake] to start it again.
 */
class LockSession(
  val rig: LockRig,
  val ui: BenchUi,
  private val onEvent: (LockEvent) -> Unit,
) {
  val lock = LockMechanics(rig.pinSpecs, onEvent = ::handle)
  val pose = RigPose()

  private val wake = Channel<Unit>(Channel.CONFLATED)
  private val openSign = sign(rig.openDegrees)

  private val plug = Eased(0f, stiffness = 120f, epsilon = 0.02f)
  private val shackleLift = Eased(0f, stiffness = 320f, damping = 15f, epsilon = 0.0003f)
  private val shackleSwing = Eased(0f, stiffness = 42f, epsilon = 0.05f)
  private val wrench = Eased(0f, stiffness = 150f, epsilon = 0.002f)
  private val pickZ = Eased(rig.pickParkedZ, stiffness = 300f, epsilon = 0.0004f)
  private val pickY = Eased(rig.pickParkedY, stiffness = 320f, epsilon = 0.0004f)
  private val explode = Eased(0f, stiffness = 170f, epsilon = 0.0005f)
  private val alpha = Eased(1f, stiffness = 90f, epsilon = 0.003f)

  /** The pin the user is pushing and how far, or -1. Applied once the pick is under it. */
  private var requestedPin = -1
  private var requestedLift = 0f
  private var relocking = false

  init {
    pose.pickZ = pickZ.value
    pose.pickY = pickY.value
    rig.apply(lock, pose)
  }

  fun wake() {
    wake.trySend(Unit)
  }

  // ── Inputs ──────────────────────────────────────────────────────────────────────────────────

  /** Put the pick under [pin] and push it to [lift]. Call again as the finger moves. */
  fun hold(pin: Int, lift: Float) {
    if (lock.opened || relocking) return
    requestedPin = pin
    requestedLift = lift
    ui.selectedPin = pin
    wake()
  }

  fun letGo() {
    requestedPin = -1
    wake()
  }

  /** A tap: slide the pick under [pin], lift it exactly to the shear line, and let go. */
  suspend fun nudge(pin: Int) {
    if (lock.opened || relocking) return
    val shear = lock.pins[pin].liftToShear
    try {
      hold(pin, 0f)
      var waited = 0
      while (!pickIsUnder(pin) && waited++ < 40) withFrameNanos { }
      animate(0f, 1f, animationSpec = tween(170, easing = FastOutSlowInEasing)) { t, _ -> hold(pin, shear * t) }
      hold(pin, shear)
      delay(110)
    } finally {
      letGo()
    }
  }

  fun setTension(on: Boolean) {
    if (lock.opened || relocking) return
    lock.applyTension(on)
    wake()
  }

  /** Close the lock again. The shackle swings home and drops before the plug turns back. */
  fun reset() {
    requestedPin = -1
    ui.selectedPin = -1
    if (lock.opened) relocking = true else lock.relock()
    wake()
  }

  // ── Frame loop ──────────────────────────────────────────────────────────────────────────────

  /** Runs until cancelled. [invalidate] is called when a material changed under SceneView. */
  suspend fun run(invalidate: () -> Unit) {
    while (true) {
      wake.receive()
      var last = 0L
      do {
        val now = withFrameNanos { it }
        val dt = if (last == 0L) 1f / 60f else ((now - last) / 1e9f).coerceIn(0f, 1f / 20f)
        last = now
        val moving = advance(dt)
        if (rig.apply(lock, pose)) invalidate()
        publish()
      } while (moving)
    }
  }

  private fun advance(dt: Float): Boolean {
    // Pin travel. A push only lands once the pick has actually arrived under the pin.
    val pin = requestedPin
    if (pin >= 0 && !lock.opened) {
      if (lock.heldPin >= 0 && lock.heldPin != pin) lock.release()
      if (pickIsUnder(pin)) lock.press(pin, requestedLift)
    } else if (lock.heldPin >= 0) {
      lock.release()
    }
    val pinsBefore = lock.keyLift.sum() + lock.driverLift.sum()
    lock.step(dt)
    if (pinsBefore != lock.keyLift.sum() + lock.driverLift.sum() || lock.heldPin >= 0) ui.motionTick++

    // Opening choreography: plug turns, then the shackle pops, then it swings. Relocking
    // runs the same three stages backwards, and only then lets the drivers fall.
    val open = lock.opened && !relocking
    shackleSwing.target = if (open && shackleLift.value > rig.shackleTravel * 0.9f) SWING_DEGREES else 0f
    shackleLift.target = when {
      open && abs(plug.value) > 80f -> rig.shackleTravel
      !open && abs(shackleSwing.value) > 2f -> rig.shackleTravel
      else -> 0f
    }
    plug.target = when {
      open -> rig.openDegrees
      relocking -> if (shackleLift.value > 0.004f) rig.openDegrees else 0f
      else -> openSign * lock.plugDegrees
    }
    if (relocking && plug.target == 0f && abs(plug.value) < 1f) {
      relocking = false
      lock.relock()
    }

    wrench.target = if (lock.tension || lock.opened) 1f else 0f

    // The pick waits under the selected pin, rides it while pushing, and parks when idle.
    val selected = if (lock.opened || relocking) -1 else ui.selectedPin
    if (selected >= 0) {
      pickZ.target = rig.pinZ[selected]
      val contact = rig.pinRestY - PICK_GAP
      if (lock.heldPin == selected) pickY.snap(contact + lock.keyLift[selected]) else pickY.target = contact
    } else {
      pickZ.target = rig.pickParkedZ
      pickY.target = rig.pickParkedY
    }

    explode.target = ui.explode
    alpha.target = if (ui.ghost) GHOST_ALPHA else 1f

    var moving = lock.isSettling || relocking
    moving = plug.step(dt) or moving
    moving = shackleLift.step(dt) or moving
    moving = shackleSwing.step(dt) or moving
    moving = wrench.step(dt) or moving
    moving = pickZ.step(dt) or moving
    moving = pickY.step(dt) or moving
    moving = explode.step(dt) or moving
    moving = alpha.step(dt) or moving

    pose.plugDegrees = plug.value
    pose.shackleLift = shackleLift.value
    pose.shackleSwingDegrees = shackleSwing.value
    pose.wrenchSeat = wrench.value
    pose.pickZ = pickZ.value
    pose.pickY = pickY.value
    pose.explode = explode.value
    pose.bodyAlpha = alpha.value
    return moving
  }

  private fun pickIsUnder(pin: Int) = abs(pickZ.value - rig.pinZ[pin]) < 0.006f

  private fun publish() {
    ui.tension = lock.tension
    ui.opened = lock.opened
    ui.setCount = lock.setCount
    ui.bindingPin = lock.bindingPin
    ui.oversetPin = lock.states.indexOf(PinState.Overset)
  }

  private fun handle(event: LockEvent) {
    when (event) {
      is LockEvent.PinSet -> ui.setPulse++
      LockEvent.Opened -> {
        ui.setPulse++
        requestedPin = -1
        ui.selectedPin = -1
      }
      else -> Unit
    }
    onEvent(event)
  }

  private companion object {
    /** Swings the toe out toward the keyway side, where the default camera can see it. */
    const val SWING_DEGREES = -72f
    const val GHOST_ALPHA = 0.14f

    /** Daylight between the hook tip and the pin it is about to lift. */
    const val PICK_GAP = 0.002f
  }
}
