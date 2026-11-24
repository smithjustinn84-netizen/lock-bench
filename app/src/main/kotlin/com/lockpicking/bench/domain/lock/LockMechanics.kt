package com.lockpicking.bench.domain.lock

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The lock as a state machine: no rendering, no Android, so it runs in a plain unit test.
 *
 * The model is single-pin picking under a continuous [torque] on the plug, 0..1.
 *
 * - Below [T_BIND] nothing binds. Above it the plug turns until the tightest pin that is not
 *   yet set stops it, and that pin binds ([bindingPin]). Lifting it to the shear line sets it.
 * - A spool driver has a narrow waist. While every pin still unset has its waist at the shear
 *   line, the plug turns much further than a set is worth: a false set ([falseSetDepth]).
 * - Getting a spool out of a false set means pushing its lower flange up through the shear
 *   line, which has to turn the plug back: counter-rotation. The pick can only do that against
 *   a light torque ([T_EASED]); from there to [T_SPOOL_BLOCK] it gets part of the way.
 * - A serrated driver has a ladder of grooves below its foot. Each one clicks as it reaches
 *   the shear line, like a set that is not one, and above [T_SERRATION] it catches there until
 *   the wrench is eased.
 * - Push a pin too far past its set and its key pin jams in the shear line. Easing the torque
 *   lets it slip back without losing the other sets.
 * - Above [T_HEAVY] the binding pin is seized and will not lift at all.
 * - Let the torque fall below [T_DROP] and everything drops.
 *
 * Pins are indexed 0-based here; [PinSpec.number] is the 1-based name used in the model.
 */
class LockMechanics(
  val pins: List<PinSpec>,
  /** Length of a driver pin. A spool's waist lies between a [SPOOL_FLANGE] at each end of it. */
  val driverLength: Float = DEFAULT_DRIVER_LENGTH,
  /** Pin indices from the tightest chamber to the loosest: among equals, the first one binds. */
  bindingOrder: List<Int> = defaultBindingOrder(pins.size),
  private val onEvent: (LockEvent) -> Unit = {},
) : LockReadout {
  /** Pin indices from the tightest chamber to the loosest. Changed only by [rekey]. */
  var bindingOrder: List<Int> = bindingOrder
    private set

  /** At or above this torque the binding pin seizes, until it falls to [seizeOffAt]. */
  var seizeAt = T_HEAVY
    private set
  var seizeOffAt = T_HEAVY_OFF
    private set

  init {
    requireOrder(bindingOrder)
  }

  /**
   * A different lock in the same housing: chambers bored to another tolerance stack-up, and
   * pins that seize at [seizeAt]. Takes effect from the next time the plug takes up.
   */
  fun rekey(order: List<Int>, seizeAt: Float = T_HEAVY, seizeOffAt: Float = seizeAt - (T_HEAVY - T_HEAVY_OFF)) {
    requireOrder(order)
    require(seizeOffAt < seizeAt && seizeAt > T_SPOOL_BLOCK) { "the seize band must sit above the spools" }
    bindingOrder = order
    this.seizeAt = seizeAt
    this.seizeOffAt = seizeOffAt
  }

  private fun requireOrder(order: List<Int>) {
    require(order.sorted() == pins.indices.toList()) { "bindingOrder must name each pin once" }
  }

  /** Torque on the plug from the wrench, 0..1. */
  var torque = 0f
    private set

  /** True while the torque is enough to hold the plug against the pins. */
  var engaged = false
    private set

  /** True while the torque is so heavy the binding pin cannot be lifted. */
  var seized = false
    private set
  var opened = false
    private set

  /** How far each key pin has risen from rest. */
  private val keyLifts = FloatArray(pins.size)

  /** How far each driver has risen from rest. Never below its key pin. */
  private val driverLifts = FloatArray(pins.size)

  private val pinStates = Array(pins.size) { PinState.Resting }

  override val pinCount: Int get() = pins.size

  override fun pin(index: Int): PinSpec = pins[index]

  override fun keyLift(index: Int): Float = keyLifts[index]

  override fun driverLift(index: Int): Float = driverLifts[index]

  override fun state(index: Int): PinState = pinStates[index]

  override fun driverTilt(index: Int): Float =
    if (pinStates[index] == PinState.Resting) min(clearance(index), falseSetDepth) else 0f

  /** What [pin] gives back to a light touch of the pick. */
  fun feel(pin: Int): PinFeel = when {
    pinStates[pin] == PinState.Set -> PinFeel.Loose
    pinStates[pin] == PinState.Overset || bindingPin == pin -> PinFeel.Binding
    else -> PinFeel.Springy
  }

  /** The pin the pick is under, or -1. */
  var heldPin = -1
    private set

  /** True from the moment the held pin sets until the pick lets go: the window for oversetting. */
  private var heldSetThisPush = false

  /** The pin the pick is pushing on without being able to move it any further, or -1. */
  var blockedPin = -1
    private set
  private var blockReported = false
  private var limitKind = Limit.Travel

  /** Key-pin height at the last [LockEvent.Grit], so the texture ticks by travel, not by frame. */
  private var gritMark = 0f

  /**
   * How far the plug has turned into the spool waists, 0..1. Zero whenever any pin that is not
   * set has a full-width part of its stack at the shear line.
   */
  var falseSetDepth = 0f
    private set
  private var inFalseSet = false
  private var counterStep = COUNTER_ROTATION_STEPS

  val setCount: Int get() = pinStates.count { it == PinState.Set }

  /** The pin jammed past the shear line, or -1. */
  val oversetPin: Int get() = pinStates.indexOf(PinState.Overset)

  /** The spool the plug is hanging on in a false set, or -1. */
  val falseSetPin: Int get() = if (inFalseSet) bindingPin else -1

  /** The pin stopping the plug, or -1 when the plug is slack or the lock is open. */
  val bindingPin: Int
    get() {
      if (!engaged || opened) return -1
      val jammed = oversetPin
      if (jammed >= 0) return jammed
      return bindingOrder.firstOrNull { pinStates[it] != PinState.Set && clearance(it) <= falseSetDepth + SNAP } ?: -1
    }

  val band: TorqueBand
    get() = when {
      !engaged -> TorqueBand.Slack
      seized -> TorqueBand.Heavy
      torque <= T_EASED -> TorqueBand.Eased
      else -> TorqueBand.Firm
    }

  /** Unsigned plug rotation the mechanism calls for, in degrees. */
  val plugDegrees: Float
    get() = when {
      opened -> OPEN_DEGREES
      engaged -> setCount * SET_DEGREES + falseSetDepth * FALSE_SET_DEGREES + torque * WINDUP_DEGREES
      else -> torque * WINDUP_DEGREES
    }

  fun maxLift(pin: Int): Float = pins[pin].liftToShear + MAX_OVERTRAVEL

  /** How far past the shear line a pin may go in the push that set it before it oversets. */
  fun oversetMargin(): Float = OVERSET_MARGIN_LIGHT + (OVERSET_MARGIN_HEAVY - OVERSET_MARGIN_LIGHT) * load()

  /**
   * How fast the pick can lift [pin], in units per second. A binding pin drags under torque,
   * and a spool forcing the plug back creeps up its shoulder: the plug has to be turned, and
   * that is the stretch of travel where the wrench is felt giving.
   */
  fun liftRate(pin: Int): Float = when {
    pinStates[pin] != PinState.Resting -> LIFT_RATE_FREE
    turningPlugBack(pin) -> LIFT_RATE_SHOULDER
    bindingPin == pin -> LIFT_RATE_LIGHT + (LIFT_RATE_HEAVY - LIFT_RATE_LIGHT) * load()
    else -> LIFT_RATE_FREE
  }

  /**
   * Where the pick gets [pin] to in [dt] seconds on its way up to [wanted]. Coming down is
   * immediate: the pick just gets out of the way.
   */
  fun reach(pin: Int, wanted: Float, dt: Float): Float {
    val current = keyLifts[pin]
    if (wanted <= current) return wanted
    val next = min(wanted, current + liftRate(pin) * dt)
    // One frame must not carry a spool clean over its shoulder: the climb is the whole event.
    val foot = shoulderFoot(pin)
    return if (plugRestsOnWaist(pin) && current < foot && next > foot) foot else next
  }

  /** True while [pin] is a spool on its lower shoulder with the plug resting on it. */
  private fun turningPlugBack(pin: Int): Boolean {
    if (!plugRestsOnWaist(pin)) return false
    val foot = shoulderFoot(pin)
    return driverLifts[pin] >= foot - SNAP && driverLifts[pin] < foot + SPOOL_SHOULDER
  }

  private fun plugRestsOnWaist(pin: Int): Boolean =
    pins[pin].spool && pinStates[pin] == PinState.Resting && engaged && falseSetDepth > 0f

  /** The lift at which the shear line meets the bottom of a spool's lower shoulder. */
  private fun shoulderFoot(pin: Int): Float = pins[pin].liftToShear - SPOOL_FLANGE - SPOOL_SHOULDER

  /** The highest the pick can push [pin] as things stand. */
  fun liftLimit(pin: Int): Float {
    limitKind = Limit.Travel
    val shear = pins[pin].liftToShear
    when (pinStates[pin]) {
      PinState.Overset -> return keyLifts[pin]
      PinState.Set -> return if (heldPin == pin && heldSetThisPush) maxLift(pin) else shear
      PinState.Resting -> Unit
    }
    if (!engaged) return maxLift(pin)
    if (seized && bindingPin == pin) {
      limitKind = Limit.Seized
      return keyLifts[pin]
    }
    if (pins[pin].serrated && torque > T_SERRATION && bindingPin == pin) {
      val catch = nextSerration(pin, driverLifts[pin])
      if (!catch.isNaN()) {
        limitKind = Limit.Serration
        return catch
      }
    }
    if (pins[pin].spool) {
      // The lowest clearance the pick may force the plug back to on this spool's lower shoulder.
      val allowed = min(falseSetDepth, spoolGate())
      if (allowed > 0f) {
        val stop = shear - SPOOL_FLANGE - allowed * SPOOL_SHOULDER
        if (driverLifts[pin] <= stop + SNAP) {
          limitKind = Limit.Spool
          return max(stop, 0f)
        }
      }
    }
    return maxLift(pin)
  }

  /** True while anything is still moving and [step] has work to do. */
  val isSettling: Boolean
    get() = pins.indices.any { i ->
      heldPin != i && pinStates[i] != PinState.Overset && (keyLifts[i] > 0f || driverLifts[i] > driverFloor(i))
    }

  /**
   * Push [pin]'s key pin to [lift] with the pick. Call repeatedly while dragging. [wanted] is
   * where the hand is trying to get it to, which may be further than [lift] when the pin drags.
   */
  fun press(pin: Int, lift: Float, wanted: Float = lift) {
    if (opened || pin !in pins.indices) return
    if (heldPin != pin) {
      heldPin = pin
      heldSetThisPush = false
      blockedPin = -1
      blockReported = false
      gritMark = keyLifts[pin]
    }
    val shear = pins[pin].liftToShear
    when (pinStates[pin]) {
      // Jammed against the bible: the pick cannot move it.
      PinState.Overset -> {
        noteHardStop(pin, wanted, keyLifts[pin])
        return
      }
      PinState.Set -> {
        if (!heldSetThisPush) {
          // The plug has turned under this driver, so the key pin has nowhere to go past the
          // shear line: its chamber no longer lines up.
          keyLifts[pin] = lift.coerceIn(0f, shear)
          noteHardStop(pin, wanted, shear)
          return
        }
        keyLifts[pin] = lift.coerceIn(0f, maxLift(pin))
        if (keyLifts[pin] > shear + oversetMargin()) {
          pinStates[pin] = PinState.Overset
          driverLifts[pin] = keyLifts[pin]
          // The jam is its own event: the stop the pick is now against needs no second knock.
          blockReported = true
          onEvent(LockEvent.PinOverset(pin))
          refreshPlug()
        }
        return
      }
      PinState.Resting -> Unit
    }

    val limit = liftLimit(pin)
    val stop = limitKind
    noteBlock(pin, wanted, limit, stop)
    val target = lift.coerceIn(0f, limit)
    val wasBinding = bindingPin == pin
    val before = keyLifts[pin]

    // Go only as far as the shear line first, so the plug follows a spool all the way up its
    // shoulder before anything is decided about a set.
    moveTo(pin, min(target, shear))
    // Every groove is below the shear line, so this stretch of the push is the one that crosses them.
    if (wasBinding && pins[pin].serrated) reportSerrations(pin, before, keyLifts[pin])
    refreshPlug()
    if (target >= shear && bindingPin == pin) {
      // A fast drag can jump the whole set window in one event; give the first crossing to
      // the set, and let only a deliberate further push overset.
      moveTo(pin, target.coerceAtMost(shear + oversetMargin()))
      pinStates[pin] = PinState.Set
      heldSetThisPush = true
      blockedPin = -1
      if (pinStates.all { it == PinState.Set }) {
        opened = true
        heldPin = -1
        heldSetThisPush = false
        clearPlug()
        onEvent(LockEvent.Opened)
      } else {
        onEvent(LockEvent.PinSet(pin, setCount))
        refreshPlug()
      }
      return
    }
    moveTo(pin, target)
    // A groove only holds once the pin is up on it, which may be this very push: the bench
    // stops asking once nothing moves, so it cannot wait for the next one to say so.
    if (stop == Limit.Serration) noteBlock(pin, wanted, limit, stop)

    if (keyLifts[pin] - gritMark >= (if (wasBinding) GRIT_STEP else SPRING_STEP)) {
      gritMark = keyLifts[pin]
      onEvent(if (wasBinding) LockEvent.Grit(pin) else LockEvent.SpringTick(pin, keyLifts[pin] / maxLift(pin)))
    } else if (keyLifts[pin] < before) {
      gritMark = min(gritMark, keyLifts[pin])
    }
  }

  /** The pick lets go; the pin it was under is back in the hands of its spring. */
  fun release() {
    heldPin = -1
    heldSetThisPush = false
    blockedPin = -1
    blockReported = false
  }

  /** Set the torque on the plug, 0..1. Call as often as the wrench moves. */
  fun applyTorque(value: Float) {
    if (opened) return
    torque = value.coerceIn(0f, 1f)
    if (!engaged && torque >= T_BIND) {
      engaged = true
      onEvent(LockEvent.TensionBite(heavy = false))
    } else if (engaged && torque < T_DROP) {
      engaged = false
      drop()
    }
    if (!seized && torque >= seizeAt) {
      seized = true
      onEvent(LockEvent.TensionBite(heavy = true))
    } else if (seized && torque < seizeOffAt) {
      seized = false
    }
    refreshPlug()
  }

  /** Close the shackle, turn the plug home, and let every pin drop. */
  fun relock() {
    val wasOpen = opened
    opened = false
    torque = 0f
    engaged = false
    seized = false
    heldPin = -1
    heldSetThisPush = false
    pinStates.fill(PinState.Resting)
    clearPlug()
    if (wasOpen) onEvent(LockEvent.Relocked)
  }

  /** Advance spring return by [dt] seconds. */
  fun step(dt: Float) {
    val jammed = oversetPin
    if (jammed >= 0 && engaged && torque <= T_EASED && heldPin != jammed) {
      // Eased off, the plug no longer pinches the key pin: it slips back, and so does its driver.
      pinStates[jammed] = PinState.Resting
      onEvent(LockEvent.OversetReleased(jammed))
    }
    val fall = FALL_SPEED * dt
    for (i in pins.indices) {
      if (pinStates[i] == PinState.Overset) continue
      if (heldPin != i) keyLifts[i] = max(0f, keyLifts[i] - fall)
      driverLifts[i] = max(driverFloor(i), driverLifts[i] - fall)
    }
    refreshPlug()
  }

  private fun moveTo(pin: Int, lift: Float) {
    keyLifts[pin] = lift
    driverLifts[pin] = max(driverLifts[pin], lift)
  }

  /** Remembers that the pick is pushing [pin] against a stop, and says so once per push. */
  private fun noteBlock(pin: Int, wanted: Float, limit: Float, kind: Limit) {
    // A serration has not caught until the pin has been pushed up onto it.
    val against = kind != Limit.Serration || keyLifts[pin] >= limit - SNAP
    if (wanted <= limit + BLOCK_SLOP || !against) {
      blockedPin = -1
      blockReported = false
      return
    }
    // The top of the chamber is just the end of the road: nothing the wrench can do about it.
    blockedPin = if (kind == Limit.Travel) -1 else pin
    if (blockReported) return
    blockReported = true
    onEvent(
      when (kind) {
        Limit.Seized -> LockEvent.Seized(pin)
        Limit.Spool -> LockEvent.SpoolBlocked(pin)
        Limit.Serration -> LockEvent.SerrationHeld(pin)
        Limit.Travel -> LockEvent.HardStop(pin)
      },
    )
  }

  /** Says once per push that the pick is against a [stop] that is solid metal. */
  private fun noteHardStop(pin: Int, wanted: Float, stop: Float) {
    if (wanted <= stop + BLOCK_SLOP) {
      blockReported = false
    } else if (!blockReported) {
      blockReported = true
      onEvent(LockEvent.HardStop(pin))
    }
  }

  /** Everything falls: the torque is no longer holding any driver on the ledge. */
  private fun drop() {
    val any = pinStates.any { it != PinState.Resting }
    pinStates.fill(PinState.Resting)
    heldSetThisPush = false
    clearPlug()
    if (any) onEvent(LockEvent.Dropped)
  }

  private fun clearPlug() {
    falseSetDepth = 0f
    inFalseSet = false
    counterStep = COUNTER_ROTATION_STEPS
    blockedPin = -1
  }

  /** Lets the plug turn as far as the pins now allow, and reports a false set or a kick back. */
  private fun refreshPlug() {
    val old = falseSetDepth
    var depth = 0f
    if (engaged && !opened) {
      depth = 1f
      var unset = 0
      for (i in pins.indices) {
        if (pinStates[i] == PinState.Set) continue
        unset++
        depth = min(depth, clearance(i))
      }
      if (unset == 0) depth = 0f
    }
    // Rounding in a driver resting on its stop must not creep the plug.
    if (abs(depth - old) < SNAP) return
    falseSetDepth = depth
    if (depth > old) {
      counterStep = counterStepAt(depth)
      if (!inFalseSet && depth >= FALSE_SET_ON) {
        inFalseSet = true
        onEvent(LockEvent.FalseSet(bindingPin))
      }
    } else {
      val step = counterStepAt(depth)
      if (step > counterStep) {
        // Only the pick turning the plug back is worth a tick; a drop is its own event.
        val notches = if (engaged && heldPin >= 0) counterStep + 1..step else IntRange.EMPTY
        counterStep = step
        for (notch in notches) onEvent(LockEvent.CounterRotation(heldPin, notch))
      }
      if (depth <= FALSE_SET_OFF) inFalseSet = false
    }
  }

  private fun counterStepAt(depth: Float): Int =
    ((1f - depth) * COUNTER_ROTATION_STEPS + SNAP).toInt().coerceIn(0, COUNTER_ROTATION_STEPS)

  /**
   * How far the plug can turn past pin [i]: 0 when a full-width part of its stack is at the
   * shear line, 1 when a spool's waist is, and in between on the shoulders either side.
   */
  private fun clearance(i: Int): Float {
    if (!pins[i].spool || pinStates[i] != PinState.Resting) return 0f
    // The height up the driver that the shear line crosses.
    val at = pins[i].liftToShear - driverLifts[i]
    val lower = (at - SPOOL_FLANGE) / SPOOL_SHOULDER
    val upper = (driverLength - SPOOL_FLANGE - at) / SPOOL_SHOULDER
    return min(lower, upper).coerceIn(0f, 1f)
  }

  /** The lift at which [pin]'s groove [k] meets the shear line. Groove 1 is the one nearest the set. */
  private fun serrationLift(pin: Int, k: Int): Float = pins[pin].liftToShear - k * SERRATION_PITCH

  /** The lift of the groove [pin] will catch on next, going up from [lift]; NaN once it is past them all. */
  private fun nextSerration(pin: Int, lift: Float): Float {
    for (k in SERRATIONS downTo 1) {
      val at = serrationLift(pin, k)
      if (at > 0f && at >= lift - SNAP) return at
    }
    return Float.NaN
  }

  /** A click for every groove that reached the shear line as the pin went from [from] up to [to]. */
  private fun reportSerrations(pin: Int, from: Float, to: Float) {
    for (k in SERRATIONS downTo 1) {
      val at = serrationLift(pin, k)
      if (at > 0f && from < at - SNAP && to >= at - SNAP) onEvent(LockEvent.SerrationClick(pin, SERRATIONS - k + 1))
    }
  }

  /** The lowest clearance the pick can force a spool's shoulder to at this torque. */
  private fun spoolGate(): Float = ((torque - T_EASED) / (T_SPOOL_BLOCK - T_EASED)).coerceIn(0f, 1f)

  /** Where the torque sits between just binding (0) and seized (1). */
  private fun load(): Float = if (engaged) ((torque - T_BIND) / (seizeAt - T_BIND)).coerceIn(0f, 1f) else 0f

  /**
   * The lowest a driver can sit: on its key pin, on the plug's ledge once set, or — for a
   * spool in a false set — hung by its upper flange on the edge of the turned plug.
   */
  private fun driverFloor(i: Int): Float {
    val ledge = when {
      pinStates[i] == PinState.Set -> pins[i].liftToShear
      pins[i].spool && falseSetDepth > 0f && pinStates[i] == PinState.Resting ->
        pins[i].liftToShear - (driverLength - SPOOL_FLANGE - falseSetDepth * SPOOL_SHOULDER)
      else -> 0f
    }
    return max(keyLifts[i], ledge)
  }

  private enum class Limit { Travel, Seized, Spool, Serration }

  companion object {
    /** Pins 3, 1, 4, 2 — an arbitrary but fixed tolerance stack-up. */
    val DEFAULT_BINDING_ORDER = listOf(2, 0, 3, 1)

    /** The order a lock of [pinCount] stacks binds in on the open bench: [DEFAULT_BINDING_ORDER], then any further pins from the back. */
    fun defaultBindingOrder(pinCount: Int): List<Int> =
      DEFAULT_BINDING_ORDER.filter { it < pinCount } + (pinCount - 1 downTo DEFAULT_BINDING_ORDER.size)

    const val DEFAULT_DRIVER_LENGTH = 0.060f

    /** Below this an engaged plug lets go and everything drops. */
    const val T_DROP = 0.05f

    /** At this the plug takes up against the pins. */
    const val T_BIND = 0.08f

    /** At or below this a spool passes freely and an overset pin slips back. */
    const val T_EASED = 0.20f

    /** At or above this the pick cannot turn the plug back at all. */
    const val T_SPOOL_BLOCK = 0.40f

    /** Above this a serration that has reached the shear line holds there; at or below it, the pick pushes it through. */
    const val T_SERRATION = 0.45f

    /** By default, at or above this the binding pin is seized, until the torque falls to [T_HEAVY_OFF]. */
    const val T_HEAVY = 0.75f
    const val T_HEAVY_OFF = 0.70f

    /** Plug rotation gained per set pin, in degrees. */
    const val SET_DEGREES = 1.5f

    /** Extra rotation of a plug turned fully into the spool waists. */
    const val FALSE_SET_DEGREES = 5f

    /** Elastic take-up of the plug and wrench at full torque. */
    const val WINDUP_DEGREES = 0.8f
    const val OPEN_DEGREES = 90f

    /** Height of the full-width flange at each end of a spool driver. */
    const val SPOOL_FLANGE = 0.017f

    /** Height of the chamfer between a spool's flange and its waist. */
    const val SPOOL_SHOULDER = 0.0035f

    /** How many grooves a serrated driver has below the set, and how far apart they are. */
    const val SERRATIONS = 3
    const val SERRATION_PITCH = 0.006f

    /** Ticks reported as the plug is turned back out of a false set. */
    const val COUNTER_ROTATION_STEPS = 4

    /** How far past the shear line the binding pin may go before it oversets (1.2 mm … 0.5 mm). */
    const val OVERSET_MARGIN_LIGHT = 0.012f
    const val OVERSET_MARGIN_HEAVY = 0.005f

    /** Total travel available above the shear line. */
    const val MAX_OVERTRAVEL = 0.028f

    /** Spring return speed in units per second. */
    const val FALL_SPEED = 0.9f

    /** How fast the pick lifts a pin: one that is free, and a binding one under light and heavy torque. */
    const val LIFT_RATE_FREE = 3f
    const val LIFT_RATE_LIGHT = 0.60f
    const val LIFT_RATE_HEAVY = 0.08f

    /** How fast a spool climbs its shoulder against the plug: about a third of a second for it all. */
    const val LIFT_RATE_SHOULDER = 0.012f

    /** Binding-pin travel between [LockEvent.Grit] ticks. */
    const val GRIT_STEP = 0.004f

    /** Travel of a pin that is free on its spring between [LockEvent.SpringTick]s. */
    const val SPRING_STEP = 0.007f

    /** How far the hand must be past a stop before the pin counts as blocked. */
    private const val BLOCK_SLOP = 0.002f
    private const val FALSE_SET_ON = 0.6f
    private const val FALSE_SET_OFF = 0.3f
    private const val SNAP = 1e-4f
  }
}
