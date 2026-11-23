package com.lockpicking.bench.domain.bench

import com.lockpicking.bench.domain.lock.LockEvent
import com.lockpicking.bench.domain.lock.LockMechanics
import com.lockpicking.bench.domain.lock.LockReadout
import com.lockpicking.bench.domain.lock.TorqueBand
import com.lockpicking.bench.domain.model.LockGeometry
import com.lockpicking.bench.domain.motion.RigPose
import com.lockpicking.bench.domain.motion.SpringValue
import kotlin.math.min

/** The coarse state of the bench: what changes a few times a gesture, not every frame. */
data class BenchSnapshot(
  val band: TorqueBand,
  val opened: Boolean,
  val setCount: Int,
  val bindingPin: Int,
  val oversetPin: Int,
  /** The spool the plug is hanging on in a false set, or -1. */
  val falseSetPin: Int,
  /** The pin the pick is pushing against a stop it cannot pass at this torque, or -1. */
  val blockedPin: Int,
  val selectedPin: Int,
  /** Bumped when a pin sets or the lock opens, to flash the shear line. */
  val setPulse: Int,
)

/**
 * The lock plus everything that moves around it, stepped one display frame at a time.
 *
 * Inputs arrive as intents ([hold], [nudge], [pullWrench], …); [advance] turns them into pin
 * travel and a [pose]. It returns false once nothing is moving, so the caller can stop asking
 * for frames: the scene renders on demand and a settled bench should cost nothing.
 *
 * Pure Kotlin: no rendering, no Android, no Compose.
 */
class BenchSimulation(
  val geometry: LockGeometry,
  private val onEvent: (LockEvent) -> Unit = {},
) {
  private val lock = LockMechanics(geometry.pinSpecs, driverLength = geometry.driverLength, onEvent = ::handle)
  private val choreography = OpenChoreography(geometry)
  private val pick = PickMotion(geometry)
  private val nudgeScript = NudgeScript()

  /** The hand on the wrench: quick, but it cannot change the torque in no time at all. */
  private val torque = SpringValue(0f, stiffness = 700f, epsilon = 0.002f)
  private val wrench = SpringValue(0f, stiffness = 150f, epsilon = 0.002f)
  private val explode = SpringValue(0f, stiffness = 170f, epsilon = 0.0005f)
  private val bodyAlpha = SpringValue(1f, stiffness = 90f, epsilon = 0.003f)

  /** Live pin travel, for whatever draws or poses the stacks. */
  val readout: LockReadout get() = lock

  val pose = RigPose()

  /** Bumped on every frame in which a pin, the plug or the torque moved, so a readout knows to redraw. */
  var motionTick = 0
    private set

  /** The pin the user is pushing and how far, or -1. Applied once the pick is under it. */
  private var requestedPin = -1
  private var requestedLift = 0f

  /**
   * Where the hand was when the held pin set, or NaN. From then on only travel beyond that
   * point pushes the pin on, so a hand that was ahead of a dragging pin does not overset it.
   */
  private var setOrigin = Float.NaN
  private var catchingUp = false
  private var selectedPin = -1
  private var setPulse = 0
  private var wrenchHeld = false
  private var torqueTarget = 0f
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
    clearRequest()
  }

  /** A tap: slide the pick under [pin], lift it to the shear line, and let go. */
  fun nudge(pin: Int) {
    if (!acceptsInput || pin !in 0 until geometry.pinCount) return
    nudgeScript.start(pin, lock.pins[pin].liftToShear)
    request(pin, 0f)
  }

  /** A hand on the wrench, pulling it to [amount] of full torque, 0..1. */
  fun pullWrench(amount: Float) {
    if (!acceptsInput) return
    wrenchHeld = true
    torqueTarget = amount.coerceIn(0f, 1f)
  }

  /** The hand comes off the wrench: the torque goes, and with it every set. */
  fun releaseWrench() {
    wrenchHeld = false
    torqueTarget = 0f
  }

  /** Close the lock again. The shackle swings home and drops before the plug turns back. */
  fun reset() {
    nudgeScript.cancel()
    clearRequest()
    selectedPin = -1
    releaseWrench()
    if (lock.opened) {
      choreography.beginRelock()
    } else {
      torque.snap(0f)
      lock.relock()
    }
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
    band = lock.band,
    opened = lock.opened,
    setCount = lock.setCount,
    bindingPin = lock.bindingPin,
    oversetPin = lock.oversetPin,
    falseSetPin = lock.falseSetPin,
    blockedPin = lock.blockedPin,
    selectedPin = selectedPin,
    setPulse = setPulse,
  )

  /** Advances everything by [dt] seconds. Returns true while anything is still moving. */
  fun advance(dt: Float): Boolean {
    torque.target = if (acceptsInput) torqueTarget else 0f
    var moving = torque.step(dt)
    lock.applyTorque(torque.value)

    runNudge(dt)
    pushRequestedPin(dt)
    stepPins(dt)

    moving = choreography.advance(dt, lock) or moving
    wrench.target = if ((wrenchHeld && acceptsInput) || lock.opened) 1f else 0f
    val selected = if (acceptsInput) selectedPin else -1
    moving = pick.advance(dt, selected, lock) or moving
    explode.target = explodeTarget
    bodyAlpha.target = if (ghost) GHOST_ALPHA else 1f

    moving = wrench.step(dt) or moving
    moving = explode.step(dt) or moving
    moving = bodyAlpha.step(dt) or moving
    moving = moving || catchingUp || lock.isSettling || choreography.relocking || nudgeScript.isActive

    val plugBefore = pose.plugDegrees
    val torqueBefore = pose.torque
    writePose()
    if (pose.plugDegrees != plugBefore || pose.torque != torqueBefore) motionTick++
    return moving
  }

  private fun request(pin: Int, lift: Float) {
    if (!acceptsInput || pin !in 0 until geometry.pinCount) return
    if (requestedPin != pin) setOrigin = Float.NaN
    requestedPin = pin
    requestedLift = lift
    selectedPin = pin
  }

  private fun clearRequest() {
    requestedPin = -1
    setOrigin = Float.NaN
    catchingUp = false
  }

  private fun runNudge(dt: Float) {
    if (!nudgeScript.isActive) return
    val pin = nudgeScript.pin
    // Still short of the shear line: the pin is dragging, or stuck until the wrench is eased.
    val short = lock.keyLift(pin) < lock.pins[pin].liftToShear - REACHED
    val lift = nudgeScript.step(dt, pick.isUnder(pin), short)
    if (lift != null) request(pin, lift) else clearRequest()
  }

  /**
   * A push only lands once the pick has actually arrived under the pin, and then only as fast
   * as the pin will move: a binding pin drags behind the hand.
   */
  private fun pushRequestedPin(dt: Float) {
    catchingUp = false
    val pin = requestedPin
    if (pin >= 0 && !lock.opened) {
      if (lock.heldPin >= 0 && lock.heldPin != pin) lock.release()
      if (!pick.isUnder(pin)) return
      val origin = setOrigin
      val wanted = if (origin.isNaN()) requestedLift else lock.pins[pin].liftToShear + (requestedLift - origin)
      lock.press(pin, lock.reach(pin, wanted, dt), wanted)
      catchingUp = !lock.opened && lock.keyLift(pin) < min(wanted, lock.liftLimit(pin)) - REACHED
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
    pose.torque = lock.torque
    pose.wrenchFlexDegrees = choreography.openSign * lock.torque * WRENCH_FLEX_DEGREES
    pose.pickZ = pick.pickZ
    pose.pickY = pick.pickY
    pose.explode = explode.value
    pose.bodyAlpha = bodyAlpha.value
  }

  private fun handle(event: LockEvent) {
    when (event) {
      is LockEvent.PinSet -> {
        setPulse++
        if (event.pin == requestedPin) setOrigin = requestedLift
      }
      LockEvent.Opened -> {
        setPulse++
        nudgeScript.cancel()
        clearRequest()
        selectedPin = -1
        wrenchHeld = false
        torqueTarget = 0f
      }
      else -> Unit
    }
    onEvent(event)
  }

  private companion object {
    const val GHOST_ALPHA = 0.14f

    /** How far the wrench's handle springs round at full torque. */
    const val WRENCH_FLEX_DEGREES = 4f

    /** Closer than this to where it is going, a pin has arrived. */
    const val REACHED = 1e-5f
  }
}
