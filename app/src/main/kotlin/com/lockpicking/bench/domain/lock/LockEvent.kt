package com.lockpicking.bench.domain.lock

sealed interface LockEvent {
  data class PinSet(val pin: Int, val setCount: Int) : LockEvent
  data class PinOverset(val pin: Int) : LockEvent

  /** The torque was eased and the jammed key pin slipped back; the other sets are still held. */
  data class OversetReleased(val pin: Int) : LockEvent

  /** The plug turned into the spool waists and is hanging on [pin]. */
  data class FalseSet(val pin: Int) : LockEvent

  /** The pick drove [pin]'s flange up and the plug back. [step] counts up to the last tick. */
  data class CounterRotation(val pin: Int, val step: Int) : LockEvent

  /** The pick is pushing a spool's flange against more torque than it can turn back. */
  data class SpoolBlocked(val pin: Int) : LockEvent

  /**
   * One of a serrated driver's grooves reached the shear line as [pin] was lifted. It clicks
   * the way a set does, but the pin is not set. [notch] counts up to the last groove before it is.
   */
  data class SerrationClick(val pin: Int, val notch: Int) : LockEvent

  /** The pick is pushing a serrated driver whose groove has caught under more torque than it can pass. */
  data class SerrationHeld(val pin: Int) : LockEvent

  /** The pick is pushing a binding pin that the torque has seized. */
  data class Seized(val pin: Int) : LockEvent

  /** A binding pin scraped a little further up its chamber. A texture, not a milestone. */
  data class Grit(val pin: Int) : LockEvent

  /** The pick's tip slid under [pin]. [feel] is what the pin gives back to a light touch. */
  data class PinTouched(val pin: Int, val feel: PinFeel) : LockEvent

  /**
   * A pin that is not binding rose a little further against its spring. A texture, like
   * [Grit]. [compression] is how far up its travel the pin is, 0..1.
   */
  data class SpringTick(val pin: Int, val compression: Float) : LockEvent

  /** The pick pushed [pin] against solid metal: the top of its travel, or a set pin's key pin at the shear line. */
  data class HardStop(val pin: Int) : LockEvent

  /** The pick was lifted into a land, the plug's metal between two chambers. */
  data object WardHit : LockEvent

  /** The pick slid a little further along a land while pressed up against it. A texture. */
  data object WardScrape : LockEvent

  /** The pick ran into the back wall of the keyway, or was drawn back to its mouth. */
  data object KeywayEnd : LockEvent

  /** The wrench took up against the pins, or with [heavy] was pulled hard enough to seize one. */
  data class TensionBite(val heavy: Boolean) : LockEvent

  /** The torque came off with pins set: every driver falls back. */
  data object Dropped : LockEvent
  data object Opened : LockEvent
  data object Relocked : LockEvent
}

/** What a pin gives back when the pick's tip first touches it. */
enum class PinFeel {
  /** Free on its spring: it gives. */
  Springy,

  /** Pinched by the plug: stiff. This is the one to lift. */
  Binding,

  /** Set: the key pin is loose under the shear line, with no spring behind it. */
  Loose,
}
