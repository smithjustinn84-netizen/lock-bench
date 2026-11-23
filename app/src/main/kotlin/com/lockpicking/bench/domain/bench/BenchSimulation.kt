package com.lockpicking.bench.domain.bench

import com.lockpicking.bench.domain.lock.LockEvent
import com.lockpicking.bench.domain.lock.LockMechanics
import com.lockpicking.bench.domain.lock.LockReadout
import com.lockpicking.bench.domain.model.LockGeometry
import com.lockpicking.bench.domain.motion.RigPose
import com.lockpicking.bench.domain.motion.SpringValue

/** The coarse state of the bench: what changes a few times a gesture, not every frame. */
data class BenchSnapshot(
  val tension: Boolean,
  val opened: Boolean,
  val setCount: Int,
  val bindingPin: Int,
  val oversetPin: Int,
  val selectedPin: Int,
  /** Bumped when a pin sets or the lock opens, to flash the shear line. */
  val setPulse: Int,
)

/**
 * The lock plus everything that moves around it, stepped one display frame at a time.
 *
 * Inputs arrive as intents ([hold], [nudge], [setTension], …); [advance] turns them into pin
 * travel and a [pose]. It returns false once nothing is moving, so the caller can stop asking
 * for frames: the scene renders on demand and a settled bench should cost nothing.
 *
 * Pure Kotlin: no rendering, no Android, no Compose.
 */
class BenchSimulation(
  val geometry: LockGeometry,
  private val onEvent: (LockEvent) -> Unit = {},
) {
  private val lock = LockMechanics(geometry.pinSpecs, onEvent = ::handle)
  private val choreography = OpenChoreography(geometry)
  private val pick = PickMotion(geometry)
  private val nudgeScript = NudgeScript()

  private val wrench = SpringValue(0f, stiffness = 150f, epsilon = 0.002f)
  private val explode = SpringValue(0f, stiffness = 170f, epsilon = 0.0005f)
  private val bodyAlpha = SpringValue(1f, stiffness = 90f, epsilon = 0.003f)

  /** Live pin travel, for whatever draws or poses the stacks. */
  val readout: LockReadout get() = lock

  val pose = RigPose()

  /** Bumped on every frame in which a pin moved, so a pin readout knows to redraw. */
  var motionTick = 0
    private set

  /** The pin the user is pushing and how far, or -1. Applied once the pick is under it. */
  private var requestedPin = -1
  private var requestedLift = 0f
  private var selectedPin = -1
  private var setPulse = 0
  private var explodeTarget = 0f
  private var ghost = false

  init {
    writePose()
  }

  private val acceptsInput: Boolean get() = !lock.opened && !choreography.relocking

  /** True while the lock is open: a pin touch then belongs to the camera, not the pick. */
  val isOpen: Boolean get() = lock.opened

  /** The furthest a pin can be pushed, in model units. */
  fun maxLift(pin: Int): Float = lock.maxLift(pin)

  // ── Inputs ──────────────────────────────────────────────────────────────────────────────────

  /** Put the pick under [pin] and push it to [lift]. Call again as the finger moves. */
  fun hold(pin: Int, lift: Float) {
    nudgeScript.cancel()
    request(pin, lift)
  }

  fun letGo() {
    nudgeScript.cancel()
    requestedPin = -1
  }

  /** A tap: slide the pick under [pin], lift it exactly to the shear line, and let go. */
  fun nudge(pin: Int) {
    if (!acceptsInput || pin !in 0 until geometry.pinCount) return
    nudgeScript.start(pin, lock.pins[pin].liftToShear)
    request(pin, 0f)
  }

  fun setTension(on: Boolean) {
    if (!acceptsInput) return
    lock.applyTension(on)
  }

  /** Close the lock again. The shackle swings home and drops before the plug turns back. */
  fun reset() {
    nudgeScript.cancel()
    requestedPin = -1
    selectedPin = -1
    if (lock.opened) choreography.beginRelock() else lock.relock()
  }

  /** 0 = assembled, 1 = fully exploded. */
  fun setExplode(amount: Float) {
    explodeTarget = amount.coerceIn(0f, 1f)
  }

  fun setGhost(on: Boolean) {
    ghost = on
  }

  // ── Output ──────────────────────────────────────────────────────────────────────────────────

  fun snapshot(): BenchSnapshot = BenchSnapshot(
    tension = lock.tension,
    opened = lock.opened,
    setCount = lock.setCount,
    bindingPin = lock.bindingPin,
    oversetPin = lock.oversetPin,
    selectedPin = selectedPin,
    setPulse = setPulse,
  )

  /** Advances everything by [dt] seconds. Returns true while anything is still moving. */
  fun advance(dt: Float): Boolean {
    runNudge(dt)
    pushRequestedPin()
    stepPins(dt)

    var moving = choreography.advance(dt, lock)
    wrench.target = if (lock.tension || lock.opened) 1f else 0f
    val selected = if (acceptsInput) selectedPin else -1
    moving = pick.advance(dt, selected, lock) or moving
    explode.target = explodeTarget
    bodyAlpha.target = if (ghost) GHOST_ALPHA else 1f

    moving = wrench.step(dt) or moving
    moving = explode.step(dt) or moving
    moving = bodyAlpha.step(dt) or moving
    moving = moving || lock.isSettling || choreography.relocking || nudgeScript.isActive

    writePose()
    return moving
  }

  private fun request(pin: Int, lift: Float) {
    if (!acceptsInput || pin !in 0 until geometry.pinCount) return
    requestedPin = pin
    requestedLift = lift
    selectedPin = pin
  }

  private fun runNudge(dt: Float) {
    if (!nudgeScript.isActive) return
    val pin = nudgeScript.pin
    val lift = nudgeScript.step(dt, pick.isUnder(pin))
    if (lift != null) request(pin, lift) else requestedPin = -1
  }

  /** A push only lands once the pick has actually arrived under the pin. */
  private fun pushRequestedPin() {
    val pin = requestedPin
    if (pin >= 0 && !lock.opened) {
      if (lock.heldPin >= 0 && lock.heldPin != pin) lock.release()
      if (pick.isUnder(pin)) lock.press(pin, requestedLift)
    } else if (lock.heldPin >= 0) {
      lock.release()
    }
  }

  private fun stepPins(dt: Float) {
    val before = totalTravel()
    lock.step(dt)
    if (before != totalTravel() || lock.heldPin >= 0) motionTick++
  }

  private fun totalTravel(): Float {
    var sum = 0f
    for (i in 0 until lock.pinCount) sum += lock.keyLift(i) + lock.driverLift(i)
    return sum
  }

  private fun writePose() {
    pose.plugDegrees = choreography.plugDegrees
    pose.shackleLift = choreography.shackleLiftValue
    pose.shackleSwingDegrees = choreography.shackleSwingDegrees
    pose.wrenchSeat = wrench.value
    pose.pickZ = pick.pickZ
    pose.pickY = pick.pickY
    pose.explode = explode.value
    pose.bodyAlpha = bodyAlpha.value
  }

  private fun handle(event: LockEvent) {
    when (event) {
      is LockEvent.PinSet -> setPulse++
      LockEvent.Opened -> {
        setPulse++
        nudgeScript.cancel()
        requestedPin = -1
        selectedPin = -1
      }
      else -> Unit
    }
    onEvent(event)
  }

  private companion object {
    const val GHOST_ALPHA = 0.14f
  }
}
