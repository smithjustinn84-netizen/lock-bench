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

  /** The pick is pushing a binding pin that the torque has seized. */
  data class Seized(val pin: Int) : LockEvent

  /** A binding pin scraped a little further up its chamber. A texture, not a milestone. */
  data class Grit(val pin: Int) : LockEvent

  /** The torque came off with pins set: every driver falls back. */
  data object Dropped : LockEvent
  data object Opened : LockEvent
  data object Relocked : LockEvent
}
