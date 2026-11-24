package com.lockpicking.bench.data

import android.os.SystemClock
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import com.lockpicking.bench.domain.game.GameClock
import com.lockpicking.bench.domain.game.GameTier
import com.lockpicking.bench.domain.model.LockModel
import kotlinx.coroutines.flow.first

/** Best times per lock and tier, kept between launches. */
interface GameRecords {
  suspend fun bestMillis(lock: LockModel, tier: GameTier): Long?

  /** Records [millis] unless a faster time is already kept. */
  suspend fun saveBest(lock: LockModel, tier: GameTier, millis: Long)
}

class DataStoreGameRecords(private val store: DataStore<Preferences>) : GameRecords {
  override suspend fun bestMillis(lock: LockModel, tier: GameTier): Long? = store.data.first()[key(lock, tier)]

  override suspend fun saveBest(lock: LockModel, tier: GameTier, millis: Long) {
    val key = key(lock, tier)
    store.edit { prefs ->
      val kept = prefs[key]
      if (kept == null || millis < kept) prefs[key] = millis
    }
  }

  /** The trainer keeps the keys it had when it was the only lock, so its records carry over. */
  private fun key(lock: LockModel, tier: GameTier): Preferences.Key<Long> {
    val tierName = tier.name.lowercase()
    return longPreferencesKey(if (lock == LockModel.Trainer) "best_$tierName" else "best_${lock.id}_$tierName")
  }
}

/** Time since boot, which keeps counting through sleep and never jumps with the wall clock. */
object SystemGameClock : GameClock {
  override fun nowMillis(): Long = SystemClock.elapsedRealtime()
}
