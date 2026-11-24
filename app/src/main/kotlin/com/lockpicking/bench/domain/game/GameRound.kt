package com.lockpicking.bench.domain.game

import com.lockpicking.bench.domain.lock.LockEvent
import kotlin.random.Random

/** How a round went. Times are in milliseconds. */
data class GameResult(
  val tier: GameTier,
  val timeMillis: Long,
  /** Times the wrench was let go with pins set and they all fell. */
  val drops: Int,
  val oversets: Int,
  /** Times the wrench was pulled hard enough to seize the binding pin. */
  val seizes: Int,
  val stars: Int,
  /** The time a clean pick had to beat for three stars. */
  val parSeconds: Int,
  /** The best time for this tier before this round, or null if there was none. */
  val previousBestMillis: Long?,
  /** The rake was used on this lock, so the time stands among rake times, not hook times. */
  val raked: Boolean = false,
) {
  val mistakes: Int get() = drops + oversets + seizes
  val newBest: Boolean get() = previousBestMillis == null || timeMillis < previousBestMillis
}

/**
 * One blind lock: which order its pins bind in, a clock that starts on the first pull of the
 * wrench and stops when the shackle releases, and a tally of the mistakes in between.
 *
 * Pure Kotlin. Time comes in as milliseconds from whatever clock the caller keeps, so the clock
 * can be paused while the app is in the background and driven by hand in a test.
 */
class GameRound(
  val tier: GameTier,
  pinCount: Int,
  random: Random,
  /** The last lock's order, so two locks in a row never bind the same way. */
  previousOrder: List<Int>? = null,
  /** Open it inside this, with no mistakes, for three stars. A harder lock gets longer than the tier's own. */
  val parSeconds: Int = tier.parSeconds,
) {
  val bindingOrder: List<Int> = shuffle(pinCount, random, previousOrder)

  private var startedAt = NOT_YET
  private var pausedAt = NOT_YET
  private var pausedFor = 0L
  private var finishedAfter = NOT_YET

  var drops = 0
    private set
  var oversets = 0
    private set
  var seizes = 0
    private set

  /** True once the rake has been put to this lock. A round is a hook round until then. */
  var raked = false
    private set

  val mistakes: Int get() = drops + oversets + seizes
  val started: Boolean get() = startedAt != NOT_YET
  val finished: Boolean get() = finishedAfter != NOT_YET
  private val running: Boolean get() = started && !finished

  /** The rake went in. There is no taking it back: the round is a rake round from here. */
  fun markRaked() {
    if (!finished) raked = true
  }

  /** The clock starts with the first touch of the wrench. Later calls do nothing. */
  fun start(now: Long) {
    if (!started) startedAt = now
  }

  fun pause(now: Long) {
    if (running && pausedAt == NOT_YET) pausedAt = now
  }

  fun resume(now: Long) {
    if (pausedAt == NOT_YET) return
    pausedFor += now - pausedAt
    pausedAt = NOT_YET
  }

  /** Time on the clock at [now]: zero before the first pull, frozen once open. */
  fun elapsed(now: Long): Long = when {
    finished -> finishedAfter
    !started -> 0L
    else -> ((if (pausedAt != NOT_YET) pausedAt else now) - startedAt - pausedFor).coerceAtLeast(0L)
  }

  /** Scores [event]. Returns true if it finished the round: the lock opened. */
  fun onEvent(event: LockEvent, now: Long): Boolean {
    if (!running) return false
    when (event) {
      LockEvent.Dropped -> drops++

      is LockEvent.PinOverset -> oversets++

      is LockEvent.TensionBite -> if (event.heavy) seizes++

      LockEvent.Opened -> {
        finishedAfter = elapsed(now)
        return true
      }

      else -> Unit
    }
    return false
  }

  /** The result of a finished round, against the tier's [previousBestMillis]. */
  fun result(previousBestMillis: Long?): GameResult {
    check(finished) { "the lock is not open yet" }
    return GameResult(tier, finishedAfter, drops, oversets, seizes, stars(), parSeconds, previousBestMillis, raked)
  }

  private fun stars(): Int = when {
    mistakes == 0 && finishedAfter <= parSeconds * 1000L -> 3
    mistakes <= 2 -> 2
    else -> 1
  }

  private companion object {
    const val NOT_YET = -1L

    fun shuffle(pinCount: Int, random: Random, avoid: List<Int>?): List<Int> {
      val pins = (0 until pinCount).toList()
      var order = pins.shuffled(random)
      while (pinCount > 1 && order == avoid) order = pins.shuffled(random)
      return order
    }
  }
}
