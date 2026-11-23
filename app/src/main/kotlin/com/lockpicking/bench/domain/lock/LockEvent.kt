package com.lockpicking.bench.domain.lock

sealed interface LockEvent {
  data class PinSet(val pin: Int, val setCount: Int) : LockEvent
  data class PinOverset(val pin: Int) : LockEvent

  /** Tension came off with pins set: every driver falls back. */
  data object Dropped : LockEvent
  data object Opened : LockEvent
  data object Relocked : LockEvent
}
