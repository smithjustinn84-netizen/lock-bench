package com.lockpicking.bench.domain.bench

import com.lockpicking.bench.domain.lock.LockEvent
import com.lockpicking.bench.domain.lock.LockMechanics
import com.lockpicking.bench.domain.lock.LockReadout
import com.lockpicking.bench.domain.lock.PinState
import com.lockpicking.bench.domain.lock.TorqueBand
import com.lockpicking.bench.domain.model.LockGeometry
import com.lockpicking.bench.domain.motion.RigPose
import com.lockpicking.bench.domain.motion.SpringValue
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

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
 * pin travel and a [pose]. The hook is somewhere along the [keyway] and can only push the pin
 * its tip is under; between pins it is under a land and knocks on it if lifted. The rake
 * ([setTool]) is a blade under several pins at once, which it carries up and down as it is
 * scrubbed along. [advance] returns false once nothing is moving, so the caller can stop asking
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

  /** The pick in hand. */
  var tool = PickTool.Hook
    private set

  /** The rake this lock comes with: without one in the model, the standard one. */
  private val rake = geometry.rake ?: RakeProfile.Standard

  /** +1 if the keyway's mouth is at the high-Z end, so "behind the tip" is toward higher Z. */
  private val outward = sign(keyway.mouthZ - keyway.backZ)

  /** How high the rake's lead peak rides, above where it touches a pin at rest. */
  private var bladeLift = 0f

  /** Where the hand had the rake's lift last frame, to bring it to this frame's a step at a time. */
  private var lastRakeRequest = 0f

  /** True while a pin under the rake is still on its way up to where the blade wants it. */
  private var rakeClimbing = false

  /** Where the pin [allowedBy] last answered for stops, as a lift of its own. */
  private var pinStopAt = 0f

  /** The blade this sub-step, for [allowedBy] and [carry]. One, reused: this runs many times a frame. */
  private val blade = BladeStep()

  /** What each pin has already been heard to do since the blade came under it, as bits of [Cue]. */
  private val cued = IntArray(geometry.pinCount)

  /** The pin the rake was last stopped by, and how long ago, so the status does not flicker hump to hump. */
  private var rakeBlockedPin = -1
  private var rakeBlockedFor = 0f

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

  /**
   * Swap the pick in hand. The one that was in the lock comes out, so the pins it held go back
   * on their springs, but the wrench stays where it is and every set with it.
   */
  fun setTool(next: PickTool) {
    if (next == tool) return
    nudgeScript.cancel()
    clearRequest()
    parkPick()
    lock.release()
    tool = next
    writePose()
  }

  /** A tap: slide the hook under [pin], lift it to the shear line, and let go. A rake is not aimed. */
  fun nudge(pin: Int) {
    if (tool != PickTool.Hook) return
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

  /**
   * Relock and swap in a different lock: pins that bind in [bindingOrder] and seize at
   * [seizeAt]. The housing and the pins are the same; only which one binds first changes.
   */
  fun deal(bindingOrder: List<Int>, seizeAt: Float = LockMechanics.T_HEAVY) {
    reset()
    lock.rekey(bindingOrder, seizeAt)
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
    blockedPin = if (tool == PickTool.Rake) rakeBlockedPin else lock.blockedPin,
    selectedPin = selectedPin,
    setPulse = setPulse,
  )

  /** Advances everything by [dt] seconds. Returns true while anything is still moving. */
  fun advance(dt: Float): Boolean {
    torque.target = if (acceptsInput) torqueTarget else 0f
    var moving = torque.step(dt)
    lock.applyTorque(torque.value)

    val depth = if (acceptsInput) pickDepth else Float.NaN
    var pickMoving = workPick(dt, depth)
    stepPins(dt)

    moving = choreography.advance(dt, lock) or moving
    wrench.target = if ((wrenchHeld && acceptsInput) || lock.opened) 1f else 0f
    if (tool == PickTool.Hook) pickMoving = pick.advance(dt, depth, requestedLift, lock)
    moving = pickMoving || rakeClimbing || moving
    explode.target = explodeTarget
    bodyAlpha.target = if (ghost) GHOST_ALPHA else 1f

    moving = wrench.step(dt) or moving
    moving = explode.step(dt) or moving
    moving = bodyAlpha.step(dt) or moving
    moving = moving || stillBusy()
    publishPose()
    return moving
  }

  /** The rake slides and scrubs, or the hook works its pin. Returns true if the rake is still sliding. */
  private fun workPick(dt: Float, depth: Float): Boolean {
    if (tool == PickTool.Rake) {
      val from = pick.pickZ
      val sliding = pick.slide(dt, depth)
      scrub(from, pick.pickZ, dt, depth.isNaN())
      return sliding
    }
    runNudge(dt)
    pushRequestedPin(dt)
    return false
  }

  /** Something that is not a spring is still on its way: a pin catching up, a relock, a nudge. */
  private fun stillBusy(): Boolean = catchingUp || lock.isSettling || choreography.relocking || nudgeScript.isActive

  /** Writes the pose, and ticks [motionTick] if the plug, the wrench or the pick moved. */
  private fun publishPose() {
    val plugBefore = pose.plugDegrees
    val torqueBefore = pose.torque
    val pickZBefore = pose.pickZ
    val pickYBefore = pose.pickY
    writePose()
    val pickMoved = pose.pickZ != pickZBefore || pose.pickY != pickYBefore
    if (pose.plugDegrees != plugBefore || pose.torque != torqueBefore || pickMoved) motionTick++
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
    bladeLift = 0f
    lastRakeRequest = 0f
    rakeClimbing = false
    rakeBlockedPin = -1
    cued.fill(0)
  }

  // ── The rake ────────────────────────────────────────────────────────────────────────────────

  /**
   * One frame of the rake: the blade has slid from [fromZ] to [toZ] and the hand's lift has
   * gone from last frame's to this one's. Played in small steps, because at scrubbing speed a
   * hump crosses a pin in a frame or two and a pin wedged up by one covers its whole travel in
   * less: taken a frame at a time, the blade would step over grooves, shoulders and sets.
   */
  private fun scrub(fromZ: Float, toZ: Float, dt: Float, parked: Boolean) {
    rakeClimbing = false
    val wantNow = if (parked) 0f else requestedLift
    val wantBefore = lastRakeRequest
    lastRakeRequest = wantNow
    selectedPin = if (parked) -1 else keyway.pinAt(toZ)
    rakeBlockedFor += dt
    if (rakeBlockedFor > BLOCK_LINGER_SECONDS) rakeBlockedPin = -1

    if (parked || !keyway.contains(toZ)) {
      // Out of the lock, or still on its way in: nothing is under the blade.
      if (lock.anyHeld) lock.release()
      cued.fill(0)
      bladeLift = 0f
      if (!parked) pick.ride(0f)
      return
    }
    // A blade that is sliding cannot lean on a pin: only one that is nearly still forces a serration's groove.
    val speed = abs(toZ - fromZ) / dt
    val authority = 1f - smooth((speed - STEADY_SPEED) / (SLIDING_SPEED - STEADY_SPEED))
    val step = dt / RAKE_STEPS
    for (n in 1..RAKE_STEPS) {
      val t = n.toFloat() / RAKE_STEPS
      rakeStep(fromZ + (toZ - fromZ) * t, wantBefore + (wantNow - wantBefore) * t, step, authority)
      if (lock.opened) return
    }
    pick.ride(bladeLift)
    val stopped = lock.blockedPin
    if (stopped >= 0) {
      rakeBlockedPin = stopped
      rakeBlockedFor = 0f
    }
  }

  /**
   * The blade at [z] with the hand asking for [want] at its lead peak.
   *
   * It is a rigid lever on the mouth of the keyway, so a point [RakeProfile] puts `o` below
   * the lead peak, a fraction `k` of the way in from the mouth, is at `lift * k + o`. The blade
   * rises to what the hand asks unless a pin under it is against a stop, or cannot be pushed
   * up that fast: the lowest of those is where it rides. Every pin it touches is then carried
   * to its height, and the rest are let go.
   */
  private fun rakeStep(z: Float, want: Float, dt: Float, authority: Float) {
    val reach = (geometry.pickFulcrumZ - z) * outward
    if (reach < MIN_REACH) {
      if (lock.anyHeld) lock.release()
      bladeLift = 0f
      return
    }
    blade.z = z
    blade.want = want
    blade.reach = reach
    blade.dt = dt
    blade.authority = authority
    var lift = want
    var capPin = -1
    var capStop = 0f
    for (i in 0 until geometry.pinCount) {
      val allows = allowedBy(i)
      if (allows < lift) {
        lift = allows
        capPin = i
        capStop = pinStopAt
      }
    }
    lift = max(lift, 0f)
    bladeLift = lift

    // In binding order, so the pin holding the plug is moved before the ones waiting on it.
    for (i in lock.bindingOrder) {
      val at = if (i == capPin) capStop else Float.NaN
      carry(i, lift, at)
      if (lock.opened) return
    }
  }

  /**
   * The highest pin [i] lets the blade's lead peak go, with its own stop in [pinStopAt]; NaN if
   * it is not under the blade, or does not stop it.
   */
  private fun allowedBy(i: Int): Float {
    val behind = (geometry.pinZ[i] - blade.z) * outward
    val o = rake.heightAt(behind)
    val k = 1f - behind / blade.reach
    if (o.isNaN() || k < MIN_LEVER) return Float.NaN
    val wanted = blade.want * k + o
    if (wanted <= lock.keyLift(i)) return Float.NaN
    val soon = lock.reach(i, wanted, blade.dt, wedge = true, authority = blade.authority)
    val limit = lock.liftLimit(i, rake = true)
    val stop = min(soon, limit)
    if (stop >= wanted) return Float.NaN
    if (soon < limit) rakeClimbing = rakeClimbing || soon > lock.keyLift(i) + REACHED
    pinStopAt = stop
    return (stop - o) / k
  }

  /**
   * Carries pin [i] to the blade at [lift], or lets it go if the blade is not touching it.
   * [capStop] is the pin's own stop if it is the one that stopped the blade, NaN if not.
   */
  private fun carry(i: Int, lift: Float, capStop: Float) {
    val behind = (geometry.pinZ[i] - blade.z) * outward
    val o = rake.heightAt(behind)
    val k = 1f - behind / blade.reach
    // The pin that stopped the blade goes to its stop exactly: arithmetic must not leave it a hair short for good.
    var at = if (o.isNaN() || k < MIN_LEVER) 0f else if (!capStop.isNaN()) capStop else lift * k + o
    // A jammed key pin stays where it is; the blade holds it only while it is up against it.
    val touching = at > 0f && (lock.state(i) != PinState.Overset || at >= lock.keyLift(i) - TOUCHING)
    if (!touching) {
      if (lock.isHeld(i)) lock.release(i)
      cued[i] = 0
      return
    }
    // An earlier pin in this step may have set and changed what this one is up against.
    at = min(at, lock.reach(i, at, blade.dt, wedge = true, authority = blade.authority))
    lock.press(i, at, wanted = blade.want * k + o, alone = false)
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
    if (under != tipPin) arriveUnder(under)
    selectedPin = under
    // A relaxed hand is not holding the pin up: it goes back on its spring.
    if (under >= 0 && requestedLift > 0f) {
      pushPin(under, dt)
      return
    }
    if (lock.heldPin >= 0) lock.release()
    setOrigin = Float.NaN
    if (under >= 0 || pickDepth.isNaN()) return
    dragOnLand()
  }

  /** The tip has come under pin [under], or under a land if it is -1. */
  private fun arriveUnder(under: Int) {
    // Arriving under a land with the tip already up, it just rides along: no fresh knock.
    wardReported = under < 0 && requestedLift > Keyway.LAND_CLEARANCE + WARD_SLOP
    scrapeMark = pick.pickZ
    tipPin = under
    setOrigin = Float.NaN
    if (under >= 0) onEvent(LockEvent.PinTouched(under, lock.feel(under)))
  }

  /** The hand pushes [pin] up, and the pin follows only as fast as it will move. */
  private fun pushPin(pin: Int, dt: Float) {
    if (lock.heldPin >= 0 && lock.heldPin != pin) lock.release()
    val origin = setOrigin
    val wanted = if (origin.isNaN()) requestedLift else lock.pins[pin].liftToShear + (requestedLift - origin)
    lock.press(pin, lock.reach(pin, wanted, dt), wanted)
    catchingUp = !lock.opened && lock.keyLift(pin) < min(wanted, lock.liftLimit(pin)) - REACHED
  }

  /** Under a land: the tip knocks on the ward once it is up there, and scrapes as it drags. */
  private fun dragOnLand() {
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
    if (before != totalTravel() || lock.anyHeld) motionTick++
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
    pose.tool = tool
    pose.pickZ = pick.pickZ
    pose.pickY = pick.pickY
    pose.explode = explode.value
    pose.bodyAlpha = bodyAlpha.value
  }

  /**
   * A rake goes over every pin several times a second, and a pin against a stop would say so on
   * every hump. Each pin is heard once for each kind of thing while the blade stays under it;
   * after that a stop is only the scrape of the blade riding under it. Solid metal, a set pin
   * or the top of a chamber, is never more than that scrape: a rake is always running into it.
   */
  private fun quietened(event: LockEvent): LockEvent? {
    val (pin, cue) = when (event) {
      is LockEvent.HardStop -> return LockEvent.WardScrape
      is LockEvent.SpoolBlocked -> event.pin to Cue.SPOOL
      is LockEvent.SerrationHeld -> event.pin to Cue.HELD
      is LockEvent.Seized -> event.pin to Cue.SEIZED
      is LockEvent.SerrationClick -> event.pin to (Cue.CLICK shl (event.notch - 1))
      else -> return event
    }
    if (cued[pin] and cue == 0) {
      cued[pin] = cued[pin] or cue
      return event
    }
    return if (event is LockEvent.SerrationClick) null else LockEvent.WardScrape
  }

  private fun handle(raw: LockEvent) {
    val event = if (tool == PickTool.Rake) quietened(raw) ?: return else raw
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

  /** Bits of [cued]. A serration click takes one bit per groove, from [CLICK] up. */
  private object Cue {
    const val SPOOL = 2
    const val HELD = 4
    const val SEIZED = 8
    const val CLICK = 16
  }

  private companion object {
    const val GHOST_ALPHA = 0.14f

    /** Steps the rake's frame is played in. */
    const val RAKE_STEPS = 12

    /** The blade's slide, in units a second, below which it pushes steadily and above which it only glances. */
    const val STEADY_SPEED = 0.015f
    const val SLIDING_SPEED = 0.06f

    /** Nearer the mouth than this the blade has no lever to speak of, and a point this little of the way in does no lifting. */
    const val MIN_REACH = 0.02f
    const val MIN_LEVER = 0.05f

    /** A blade within this of a jammed key pin's tip is still holding it. */
    const val TOUCHING = 0.001f

    /** How long the status goes on naming the pin that last stopped the rake. */
    const val BLOCK_LINGER_SECONDS = 0.5f

    // Smoothstep, 3x² − 2x³: the coefficients are the curve, not tuning.
    @Suppress("MagicNumber")
    fun smooth(t: Float): Float {
      val x = t.coerceIn(0f, 1f)
      return x * x * (3f - 2f * x)
    }

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

/** One sub-step of the rake's blade: where it is, what the hand asks of it, and how steadily. */
private class BladeStep {
  var z = 0f
  var want = 0f
  var reach = 0f
  var dt = 0f
  var authority = 0f
}
