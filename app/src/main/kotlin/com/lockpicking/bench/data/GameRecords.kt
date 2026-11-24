package com.lockpicking.bench.data

import android.content.Context
import android.os.SystemClock
import com.lockpicking.bench.domain.game.GameClock
import com.lockpicking.bench.domain.game.GameTier

/** Best times per tier, kept between launches. */
interface GameRecords {
  fun bestMillis(tier: GameTier): Long?

  fun saveBest(tier: GameTier, millis: Long)
}

/** A handful of numbers: SharedPreferences is plenty. */
class PrefsGameRecords(context: Context) : GameRecords {
  private val prefs = context.getSharedPreferences("game_records", Context.MODE_PRIVATE)

  override fun bestMillis(tier: GameTier): Long? = prefs.getLong(key(tier), -1L).takeIf { it >= 0L }

  override fun saveBest(tier: GameTier, millis: Long) {
    prefs.edit().putLong(key(tier), millis).apply()
  }

  private fun key(tier: GameTier) = "best_${tier.name.lowercase()}"
}

/** Time since boot, which keeps counting through sleep and never jumps with the wall clock. */
object SystemGameClock : GameClock {
  override fun nowMillis(): Long = SystemClock.elapsedRealtime()
}
