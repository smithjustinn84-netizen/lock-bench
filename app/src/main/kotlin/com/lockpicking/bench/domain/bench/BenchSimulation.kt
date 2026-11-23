package com.lockpicking.bench.domain.bench

import com.lockpicking.bench.domain.lock.LockEvent
import com.lockpicking.bench.domain.lock.LockMechanics
import com.lockpicking.bench.domain.lock.LockReadout
import com.lockpicking.bench.domain.lock.TorqueBand
import com.lockpicking.bench.domain.model.LockGeometry
import com.lockpicking.bench.domain.motion.RigPose
import com.lockpicking.bench.domain.motion.SpringValue
import kotlin.math.abs
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
  /** The pin the pick's tip is under, or -1 when it is under a land or parked. */
  val selectedPin: Int,
  /** Bumped when a pin sets or the lock opens, to flash the shear line. */
  val setPulse: Int,
)

/**
 * The lock plus everything that moves around it, stepped one display frame at a time.
 *
 * Inputs arrive as intents ([movePick], [nudge], [pullWrench], …); [advance] turns them into
 * pin travel and a [pose]. The pick is somewhere along the [keyway] and can only push the pin
 * its tip is under; between pins it is under a land and knocks on it if lifted. It returns false once nothing is moving, so the caller can stop asking
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
  val keyway = Keyway(geometry)
  private val pick = PickMotion(geometry, keyway)
  private val nudgeScript = NudgeScript()

  /** The hand on the wrench: quick, but it cannot change the torque in no time at all. */
  private val torque = SpringValue(0f, stiffness = 700f, epsilon = 0.002f)
  private val wrench = SpringValue(0f, stiffness = 150f, epsilon = 0.002f)
  private val explode = SpringValue(0f, stiffness = 170f, epsilon = 0.0005f)
  private val bodyAlpha = SpringValue(1f, stiffness = 90f, epsilon = 0.003f)

  /** Live pin travel, for whatever draws or poses the stacks. */
  val readout: LockReadout get() = lock

  val pose = RigPose()

  /** Bumped on every frame in which a pin, the pick, the plug or the torque moved, so a readout knows to redraw. */
  var motionTick = 0
    private set

  /** Where the hand has the pick along the keyway, 0..1, or NaN while it is parked outside. */
  private var pickDepth = Float.NaN

  /** How high the hand is trying to lift the tip, in model units. */
  private var requestedLift = 0f

  /** The pin the tip was under last frame, to tell when it arrives under another. */
  private var tipPin = -1

  /** True once the knock of the tip against the current land has been reported. */
  private var wardReported = false
  private var scrapeMark = 0f

  /** The end of the keyway the hand is pushing on and has been told about: -1 the mouth, 1 the back, 0 neither. */
  private var endReported = 0

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

  /** Where the pick is along the keyway, 0 at the mouth to 1 at the back; 0 while parked. */
  val pickDepthNow: Float get() = if (pickDepth.isNaN()) 0f else pickDepth

  /** The most [movePick] can ask for: the travel of the longest-travelling pin. */
  val liftRange: Float = (0 until geometry.pinCount).maxOf { lock.maxLift(it) }

  /**
   * The hand moves the pick to [depth] along the keyway, 0 at the mouth to 1 at the back wall,
   * and tries to lift its tip to [lift]. Call again as the thumb moves. A [depth] outside 0..1
   * is the hand pushing on the end of the keyway.
   */
  fun movePick(depth: Float, lift: Float) {
    nudgeScript.cancel()
    if (!acceptsInput) return
    val end = if (depth < -END_SLOP) -1 else if (depth > 1f + END_SLOP) 1 else 0
    if (end != 0) {
      if (endReported != end) {
        endReported = end
        onEvent(LockEvent.KeywayEnd)
      }
    } else if (depth in 0f..1f) {
      endReported = 0
    }
    pickDepth = depth.coerceIn(0f, 1f)
    requestedLift = lift.coerceAtLeast(0f)
  }

  /** Put the pick under [pin] and push it to [lift]. */
  fun hold(pin: Int, lift: Float) {
    if (pin !in 0 until geometry.pinCount) return
    movePick(keyway.depthOf(pin), lift)
  }

  /** The hand relaxes: the tip drops, but the pick stays where it is in the keyway. */
  fun letGo() {
    nudgeScript.cancel()
    clearRequest()
  }

  /** A tap: slide the pick under [pin], lift it to the shear line, and let go. */
  fun nudge(pin: Int) {
    if (!acceptsInput || pin !in 0 until geometry.pinCount) return
    nudgeScript.start(pin, lock.pins[pin].liftToShear)
    pickDepth = keyway.depthOf(pin)
    requestedLift = 0f
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
    parkPick()
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
    moving = pick.advance(dt, if (acceptsInput) pickDepth else Float.NaN, requestedLift, lock) or moving
    explode.target = explodeTarget
    bodyAlpha.target = if (ghost) GHOST_ALPHA else 1f

    moving = wrench.step(dt) or moving
    moving = explode.step(dt) or moving
    moving = bodyAlpha.step(dt) or moving
    moving = moving || catchingUp || lock.isSettling || choreography.relocking || nudgeScript.isActive

    val plugBefore = pose.plugDegrees
    val torqueBefore = pose.torque
    val pickZBefore = pose.pickZ
    val pickYBefore = pose.pickY
    writePose()
    val pickMoved = pose.pickZ != pickZBefore || pose.pickY != pickYBefore
    if (pose.plugDegrees != plugBefore || pose.torque != torqueBefore || pickMoved) motionTick++
    return moving
  }

  private fun clearRequest() {
    requestedLift = 0f
    setOrigin = Float.NaN
    catchingUp = false
  }

  private fun parkPick() {
    pickDepth = Float.NaN
    tipPin = -1
    selectedPin = -1
    wardReported = false
    endReported = 0
  }

  private fun runNudge(dt: Float) {
    if (!nudgeScript.isActive) return
    val pin = nudgeScript.pin
    // Still short of the shear line: the pin is dragging, or stuck until the wrench is eased.
    val short = lock.keyLift(pin) < lock.pins[pin].liftToShear - REACHED
    val lift = nudgeScript.step(dt, pick.isUnder(pin), short)
    if (lift != null) {
      pickDepth = keyway.depthOf(pin)
      requestedLift = lift
    } else {
      clearRequest()
    }
  }

  /**
   * What the tip is doing this frame. Under a pin, the push lands on it, and then only as fast
   * as the pin will move: a binding pin drags behind the hand. Under a land there is nothing
   * to push, only metal to knock on.
   */
  private fun pushRequestedPin(dt: Float) {
    catchingUp = false
    val under = if (pickDepth.isNaN() || !acceptsInput) -1 else pick.pinUnderTip
    if (under != tipPin) {
      // Arriving under a land with the tip already up, it just rides along: no fresh knock.
      wardReported = under < 0 && requestedLift > Keyway.LAND_CLEARANCE + WARD_SLOP
      scrapeMark = pick.pickZ
      tipPin = under
      setOrigin = Float.NaN
      if (under >= 0) onEvent(LockEvent.PinTouched(under, lock.feel(under)))
    }
    selectedPin = under
    // A relaxed hand is not holding the pin up: it goes back on its spring.
    if (under >= 0 && requestedLift > 0f) {
      if (lock.heldPin >= 0 && lock.heldPin != under) lock.release()
      val origin = setOrigin
      val wanted = if (origin.isNaN()) requestedLift else lock.pins[under].liftToShear + (requestedLift - origin)
      lock.press(under, lock.reach(under, wanted, dt), wanted)
      catchingUp = !lock.opened && lock.keyLift(under) < min(wanted, lock.liftLimit(under)) - REACHED
      return
    }
    if (lock.heldPin >= 0) lock.release()
    setOrigin = Float.NaN
    if (under >= 0 || pickDepth.isNaN()) return
    if (requestedLift > Keyway.LAND_CLEARANCE + WARD_SLOP) {
      if (!wardReported) {
        wardReported = true
        scrapeMark = pick.pickZ
        onEvent(LockEvent.WardHit)
      } else if (abs(pick.pickZ - scrapeMark) >= SCRAPE_STEP) {
        scrapeMark = pick.pickZ
        onEvent(LockEvent.WardScrape)
      }
    } else {
      wardReported = false
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
        if (event.pin == tipPin) setOrigin = requestedLift
      }
      LockEvent.Opened -> {
        setPulse++
        nudgeScript.cancel()
        clearRequest()
        parkPick()
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

    /** How far past the end of the keyway the hand must push before it counts as hitting it. */
    const val END_SLOP = 0.02f

    /** How far above a land's clearance the hand must lift before the tip knocks on it. */
    const val WARD_SLOP = 0.002f

    /** Travel along a land, pressed against it, between [LockEvent.WardScrape]s. */
    const val SCRAPE_STEP = 0.006f

    /** Closer than this to where it is going, a pin has arrived. */
    const val REACHED = 1e-5f
  }
}
