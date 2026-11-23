package com.lockpicking.bench.lock

import kotlin.math.max

/**
 * One pin stack, in model units (1 unit = 0.1 m). Read from the glTF node extras.
 *
 * @property liftToShear how far the key pin must rise for the gap between it and its driver
 *   to sit exactly on the shear line.
 * @property springRest the spring's uncompressed length, used to scale it as the driver rises.
 */
data class PinSpec(
  val number: Int,
  val liftToShear: Float,
  val springRest: Float,
  val spool: Boolean = false,
)

enum class PinState {
  /** Driver straddles the shear line and blocks the plug. */
  Resting,

  /** Driver is trapped above the shear line on the ledge of the turned plug. */
  Set,

  /** The key pin itself was pushed across the shear line and is jammed there. */
  Overset,
}

sealed interface LockEvent {
  data class PinSet(val pin: Int, val setCount: Int) : LockEvent
  data class PinOverset(val pin: Int) : LockEvent

  /** Tension came off with pins set: every driver falls back. */
  data object Dropped : LockEvent
  data object Opened : LockEvent
  data object Relocked : LockEvent
}

/**
 * The lock as a state machine: no rendering, no Android, so it runs in a plain unit test.
 *
 * The model is single-pin picking. With tension on, manufacturing tolerance makes exactly one
 * pin bind at a time ([bindingOrder]); lifting that pin to the shear line sets it and lets the
 * plug creep round a little, which moves the bind to the next pin. A pin that is not binding
 * just rides its spring. Push the binding pin too far and its key pin crosses the shear line
 * and jams — the only way out is to let the tension off, which drops everything.
 *
 * Pins are indexed 0-based here; [PinSpec.number] is the 1-based name used in the model.
 */
class LockMechanics(
  val pins: List<PinSpec>,
  /** Pin indices in the order they bind. */
  val bindingOrder: List<Int> = DEFAULT_BINDING_ORDER.filter { it < pins.size },
  private val onEvent: (LockEvent) -> Unit = {},
) {
  init {
    require(bindingOrder.sorted() == pins.indices.toList()) { "bindingOrder must name each pin once" }
  }

  var tension = false
    private set
  var opened = false
    private set

  /** How far each key pin has risen from rest. */
  val keyLift = FloatArray(pins.size)

  /** How far each driver has risen from rest. Never below its key pin. */
  val driverLift = FloatArray(pins.size)

  val states = Array(pins.size) { PinState.Resting }

  /** The pin the pick is under, or -1. */
  var heldPin = -1
    private set

  /** True from the moment the held pin sets until the pick lets go: the window for oversetting. */
  private var heldSetThisPush = false

  val setCount: Int get() = states.count { it == PinState.Set }

  /** The pin currently binding, or -1 when there is no tension or the lock is open. */
  val bindingPin: Int
    get() = if (!tension || opened) -1 else bindingOrder.firstOrNull { states[it] != PinState.Set } ?: -1

  /** Unsigned plug rotation the mechanism calls for, in degrees. */
  val plugDegrees: Float
    get() = when {
      opened -> OPEN_DEGREES
      tension -> setCount * SET_DEGREES
      else -> 0f
    }

  fun maxLift(pin: Int): Float = pins[pin].liftToShear + MAX_OVERTRAVEL

  /** True while anything is still moving and [step] has work to do. */
  val isSettling: Boolean
    get() = pins.indices.any { i ->
      heldPin != i && states[i] != PinState.Overset && (keyLift[i] > 0f || driverLift[i] > driverFloor(i))
    }

  /** Push [pin]'s key pin to [lift] with the pick. Call repeatedly while dragging. */
  fun press(pin: Int, lift: Float) {
    if (opened || pin !in pins.indices) return
    if (heldPin != pin) {
      heldPin = pin
      heldSetThisPush = false
    }
    val shear = pins[pin].liftToShear
    when (states[pin]) {
      // Jammed against the bible: the pick cannot move it.
      PinState.Overset -> return
      PinState.Set -> {
        if (!heldSetThisPush) {
          // The plug has turned under this driver, so the key pin has nowhere to go past the
          // shear line: its chamber no longer lines up.
          keyLift[pin] = lift.coerceIn(0f, shear)
          return
        }
        keyLift[pin] = lift.coerceIn(0f, maxLift(pin))
        if (keyLift[pin] > shear + OVERSET_MARGIN) {
          states[pin] = PinState.Overset
          driverLift[pin] = keyLift[pin]
          onEvent(LockEvent.PinOverset(pin))
        }
        return
      }
      PinState.Resting -> Unit
    }

    var target = lift.coerceIn(0f, maxLift(pin))
    val binding = tension && bindingPin == pin
    if (binding && target >= shear) {
      // A fast drag can jump the whole set window in one event; give the first crossing to
      // the set, and let only a deliberate further push overset.
      target = target.coerceAtMost(shear + OVERSET_MARGIN)
      states[pin] = PinState.Set
      heldSetThisPush = true
      keyLift[pin] = target
      driverLift[pin] = max(driverLift[pin], target)
      if (states.all { it == PinState.Set }) {
        opened = true
        heldPin = -1
        heldSetThisPush = false
        onEvent(LockEvent.Opened)
      } else {
        onEvent(LockEvent.PinSet(pin, setCount))
      }
      return
    }
    keyLift[pin] = target
    driverLift[pin] = max(driverLift[pin], target)
  }

  /** The pick lets go; the pin it was under is back in the hands of its spring. */
  fun release() {
    heldPin = -1
    heldSetThisPush = false
  }

  fun applyTension(on: Boolean) {
    if (tension == on || opened) return
    tension = on
    if (!on && states.any { it != PinState.Resting }) {
      states.fill(PinState.Resting)
      heldSetThisPush = false
      onEvent(LockEvent.Dropped)
    }
  }

  /** Close the shackle, turn the plug home, and let every pin drop. */
  fun relock() {
    val wasOpen = opened
    opened = false
    tension = false
    heldPin = -1
    heldSetThisPush = false
    states.fill(PinState.Resting)
    if (wasOpen) onEvent(LockEvent.Relocked)
  }

  /** Advance spring return by [dt] seconds. */
  fun step(dt: Float) {
    val fall = FALL_SPEED * dt
    for (i in pins.indices) {
      if (states[i] == PinState.Overset) continue
      if (heldPin != i) keyLift[i] = max(0f, keyLift[i] - fall)
      driverLift[i] = max(driverFloor(i), driverLift[i] - fall)
    }
  }

  /** The lowest a driver can sit: on its key pin, or on the plug's ledge once set. */
  private fun driverFloor(i: Int): Float =
    max(keyLift[i], if (states[i] == PinState.Set) pins[i].liftToShear else 0f)

  companion object {
    /** Pins 3, 1, 4, 2 — an arbitrary but fixed tolerance stack-up. */
    val DEFAULT_BINDING_ORDER = listOf(2, 0, 3, 1)

    /** Plug rotation gained per set pin, in degrees. */
    const val SET_DEGREES = 1.5f
    const val OPEN_DEGREES = 90f

    /** How far past the shear line the binding pin may go before it oversets (1 mm). */
    const val OVERSET_MARGIN = 0.010f

    /** Total travel available above the shear line. */
    const val MAX_OVERTRAVEL = 0.028f

    /** Spring return speed in units per second. */
    const val FALL_SPEED = 0.9f
  }
}
