package com.lockpicking.bench.data

import android.content.Context
import android.os.SystemClock
import com.lockpicking.bench.domain.game.GameClock
import com.lockpicking.bench.domain.game.GameTier
import com.lockpicking.bench.domain.model.LockModel

/** Best times per lock and tier, kept between launches. */
interface GameRecords {
  fun bestMillis(lock: LockModel, tier: GameTier): Long?

  fun saveBest(lock: LockModel, tier: GameTier, millis: Long)
}

/** A handful of numbers: SharedPreferences is plenty. */
class PrefsGameRecords(context: Context) : GameRecords {
  private val prefs = context.getSharedPreferences("game_records", Context.MODE_PRIVATE)

  override fun bestMillis(lock: LockModel, tier: GameTier): Long? = prefs.getLong(key(lock, tier), -1L).takeIf { it >= 0L }

  override fun saveBest(lock: LockModel, tier: GameTier, millis: Long) {
    prefs.edit().putLong(key(lock, tier), millis).apply()
  }

  /** The trainer keeps the keys it had when it was the only lock, so its records carry over. */
  private fun key(lock: LockModel, tier: GameTier): String {
    val tierName = tier.name.lowercase()
    return if (lock == LockModel.Trainer) "best_$tierName" else "best_${lock.id}_$tierName"
  }
}

/** Time since boot, which keeps counting through sleep and never jumps with the wall clock. */
object SystemGameClock : GameClock {
  override fun nowMillis(): Long = SystemClock.elapsedRealtime()
}
